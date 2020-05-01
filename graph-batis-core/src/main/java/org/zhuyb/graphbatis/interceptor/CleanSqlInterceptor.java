package org.zhuyb.graphbatis.interceptor;

import com.google.common.base.CaseFormat;
import graphql.language.Field;
import graphql.language.Selection;
import graphql.language.SelectionSet;
import graphql.schema.DataFetchingEnvironment;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.*;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * @author zhuyb
 * @date 2020/4/25
 */
@Intercepts(
        {
                @Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}),
        }
)
public class CleanSqlInterceptor implements Interceptor {

    public static final Logger logger = LoggerFactory.getLogger(CleanSqlInterceptor.class);
    public static final int BOUND_SQL_INDEX = 5;
    public static final int MAPPED_STATEMENT_INDEX = 0;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object result;
        Object[] args = invocation.getArgs();
        MappedStatement mappedStatement = (MappedStatement) args[MAPPED_STATEMENT_INDEX];
        if (!SqlCommandType.SELECT.equals(mappedStatement.getSqlCommandType())) {
            result = invocation.proceed();
        } else {
            DataFetchingEnvironment dataFetchingEnvironment = DataFetchingEnvHolder.get();
            if (dataFetchingEnvironment != null) {
                Invocation changedInvocation = new Invocation(invocation.getTarget(), invocation.getMethod(), args);
                BoundSql originBoundSql = (BoundSql) args[BOUND_SQL_INDEX];
                String originSql = originBoundSql.getSql();
                logger.info("origin sql {}", originSql);
                String cleanSql = getCleanSql(dataFetchingEnvironment, originSql);
                BoundSql cleanBoundSql = new BoundSql(mappedStatement.getConfiguration(), cleanSql, originBoundSql.getParameterMappings(), originBoundSql.getParameterObject());
                args[BOUND_SQL_INDEX] = cleanBoundSql;
                result = changedInvocation.proceed();
                DataFetchingEnvHolder.remove();
            } else {
                result = invocation.proceed();
            }
        }
        return result;
    }

    /**
     * 获取过滤后的SQL
     *
     * @param dataFetchingEnvironment
     * @param originSql
     * @return
     * @throws JSQLParserException
     */
    private String getCleanSql(DataFetchingEnvironment dataFetchingEnvironment, String originSql) throws JSQLParserException {
        Select cleanSelectSql = (Select) CCJSqlParserUtil.parse(originSql);
        PlainSelect selectBody = (PlainSelect) cleanSelectSql.getSelectBody();
        Set<String> allGraphQLFieldNames = getAllGraphQLFieldNames(dataFetchingEnvironment);
        Set<String> cleanTableAlias = new HashSet<>();
        addSelectTablesAndCleanSelectItems(selectBody, allGraphQLFieldNames, cleanTableAlias);
        addWhereTables(selectBody, cleanTableAlias);
        cleanJoins(selectBody, cleanTableAlias);
        return cleanSelectSql.toString();
    }

    /**
     * 过滤关联表
     *
     * @param selectBody
     * @param cleanTableAlias
     */
    private void cleanJoins(PlainSelect selectBody, Set<String> cleanTableAlias) {
        List<Join> originJoins = selectBody.getJoins();
        List<Join> cleanJoins = new ArrayList<>();
        for (Join join : originJoins) {
            Table table = (Table) join.getRightItem();
            Alias tableAlias = table.getAlias();
            if (cleanTableAlias.contains(tableAlias.getName())) {
                cleanJoins.add(join);
            } else {
                logger.info("table {} removed", table.getName());
            }
        }
        selectBody.setJoins(cleanJoins);
    }

    /**
     * 添加where中用到的表
     *
     * @param selectBody
     * @param cleanTableAlias
     */
    private void addWhereTables(PlainSelect selectBody, Set<String> cleanTableAlias) {
        //从前台取后台取都行
//            Set<String> allGraphQLArgumentsNames = getAllGraphQLArgumentsNames(dataFetchingEnvironment);
        Expression where = selectBody.getWhere();
        BinaryExpression binaryExpression = (BinaryExpression) where;
        List<Column> expressions = new ArrayList<>();
        nextExpression(binaryExpression, expressions);
        for (Column expression : expressions) {
            cleanTableAlias.add(expression.getTable().toString());
        }
    }

    /**
     * 添加select中用到的表,清理不需要的select字段
     *
     * @param selectBody
     * @param allGraphQLFieldNames
     * @param cleanTableAlias
     */
    private void addSelectTablesAndCleanSelectItems(PlainSelect selectBody, Set<String> allGraphQLFieldNames, Set<String> cleanTableAlias) {
        List<SelectItem> originSelectItems = selectBody.getSelectItems();
        List<SelectItem> cleanSelectItems = new ArrayList<>();
        //注意这里只能取到别名partItems
        for (SelectItem selectItem : originSelectItems) {
            SelectExpressionItem selectExpressionItem = (SelectExpressionItem) selectItem;
            Column column = (Column) selectExpressionItem.getExpression();
            String columnName = column.getColumnName();
            if (allGraphQLFieldNames.contains(columnName)) {
                cleanSelectItems.add(selectItem);
                cleanTableAlias.add(column.getTable().toString());
            } else {
                logger.info("column {} removed", column.getColumnName());
            }
        }
        selectBody.setSelectItems(cleanSelectItems);
    }

    /**
     * 递归获取所有where条件
     *
     * @param expression
     * @param columns
     */
    private void nextExpression(BinaryExpression expression, List<Column> columns) {
        Expression leftExpression = (expression).getLeftExpression();
        if (leftExpression != null && leftExpression instanceof BinaryExpression) {
            nextExpression((BinaryExpression) leftExpression, columns);
        }
        if (leftExpression != null && leftExpression instanceof Column) {
            columns.add((Column) leftExpression);
        }
        Expression rightExpression = expression.getRightExpression();
        if (rightExpression != null && rightExpression instanceof BinaryExpression) {
            nextExpression((BinaryExpression) rightExpression, columns);
        }
        if (rightExpression != null && rightExpression instanceof Column) {
            columns.add((Column) rightExpression);
        }
    }

    /**
     * 获取GraphQL传入的参数名
     *
     * @param dataFetchingEnvironment
     * @return
     */
    private Set<String> getAllGraphQLArgumentsNames(DataFetchingEnvironment dataFetchingEnvironment) {
        Set<String> allGraphQLParamNames = null;
        Map<String, Object> arguments = dataFetchingEnvironment.getArguments();
        if (arguments != null) {
            allGraphQLParamNames = arguments.keySet()
                    .stream()
                    .map(s -> CaseFormat.LOWER_CAMEL.converterTo(CaseFormat.LOWER_UNDERSCORE).convert(s))
                    .collect(Collectors.toSet());
        }
        return allGraphQLParamNames;
    }

    /**
     * 获取GraphQL查询的字段名
     *
     * @param dataFetchingEnvironment
     * @return
     */
    private Set<String> getAllGraphQLFieldNames(DataFetchingEnvironment dataFetchingEnvironment) {
        Set<String> fieldNames = null;
        List<Field> fields = dataFetchingEnvironment.getFields();
        if (fields != null) {
            fieldNames = new HashSet<>();
            for (Field field : fields) {
                getAllGraphQLFieldNames(fieldNames, field);
            }
            if (fieldNames != null) {
                fieldNames = fieldNames
                        .stream()
                        .map(s -> CaseFormat.LOWER_CAMEL.converterTo(CaseFormat.LOWER_UNDERSCORE).convert(s))
                        .collect(Collectors.toSet());
            }
        }
        return fieldNames;
    }

    /**
     * 递归获取GraphQL所有查询的字段名
     *
     * @param fieldNames
     * @param field
     */
    private void getAllGraphQLFieldNames(Set<String> fieldNames, Field field) {
        if (field != null) {
            SelectionSet selectionSet = field.getSelectionSet();
            if (selectionSet != null) {
                List<Selection> selections = selectionSet.getSelections();
                if (selections != null) {
                    for (Selection selection : selections) {
                        Field subField = (Field) selection;
                        fieldNames.add(subField.getName());
                        getAllGraphQLFieldNames(fieldNames, subField);
                    }
                }
            }
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        logger.info("properties {}", properties);
    }

}