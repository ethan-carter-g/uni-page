package com.github.ethancarter.unipage.jdbc.sqlmodifier;

import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.*;

import java.util.*;

public class CountModifier implements SelectSqlModifier {

    public static final String KEEP_ORDERBY = "/*keep orderby*/";
    private static final Alias TABLE_ALIAS;

    private final Set<String> skipFunctions = Collections.synchronizedSet(new HashSet<>());
    private final Set<String> falseFunctions = Collections.synchronizedSet(new HashSet<>());

    /**
     * 聚合函数，以下列函数开头的都认为是聚合函数
     */
    private static final Set<String> AGGREGATE_FUNCTIONS = new HashSet<String>(Arrays.asList(
            ("APPROX_COUNT_DISTINCT," +
                    "ARRAY_AGG," +
                    "AVG," +
                    "BIT_," +
                    //"BIT_AND," +
                    //"BIT_OR," +
                    //"BIT_XOR," +
                    "BOOL_," +
                    //"BOOL_AND," +
                    //"BOOL_OR," +
                    "CHECKSUM_AGG," +
                    "COLLECT," +
                    "CORR," +
                    //"CORR_," +
                    //"CORRELATION," +
                    "COUNT," +
                    //"COUNT_BIG," +
                    "COVAR," +
                    //"COVAR_POP," +
                    //"COVAR_SAMP," +
                    //"COVARIANCE," +
                    //"COVARIANCE_SAMP," +
                    "CUME_DIST," +
                    "DENSE_RANK," +
                    "EVERY," +
                    "FIRST," +
                    "GROUP," +
                    //"GROUP_CONCAT," +
                    //"GROUP_ID," +
                    //"GROUPING," +
                    //"GROUPING," +
                    //"GROUPING_ID," +
                    "JSON_," +
                    //"JSON_AGG," +
                    //"JSON_ARRAYAGG," +
                    //"JSON_OBJECT_AGG," +
                    //"JSON_OBJECTAGG," +
                    //"JSONB_AGG," +
                    //"JSONB_OBJECT_AGG," +
                    "LAST," +
                    "LISTAGG," +
                    "MAX," +
                    "MEDIAN," +
                    "MIN," +
                    "PERCENT_," +
                    //"PERCENT_RANK," +
                    //"PERCENTILE_CONT," +
                    //"PERCENTILE_DISC," +
                    "RANK," +
                    "REGR_," +
                    "SELECTIVITY," +
                    "STATS_," +
                    //"STATS_BINOMIAL_TEST," +
                    //"STATS_CROSSTAB," +
                    //"STATS_F_TEST," +
                    //"STATS_KS_TEST," +
                    //"STATS_MODE," +
                    //"STATS_MW_TEST," +
                    //"STATS_ONE_WAY_ANOVA," +
                    //"STATS_T_TEST_*," +
                    //"STATS_WSR_TEST," +
                    "STD," +
                    //"STDDEV," +
                    //"STDDEV_POP," +
                    //"STDDEV_SAMP," +
                    //"STDDEV_SAMP," +
                    //"STDEV," +
                    //"STDEVP," +
                    "STRING_AGG," +
                    "SUM," +
                    "SYS_OP_ZONE_ID," +
                    "SYS_XMLAGG," +
                    "VAR," +
                    //"VAR_POP," +
                    //"VAR_SAMP," +
                    //"VARIANCE," +
                    //"VARIANCE_SAMP," +
                    //"VARP," +
                    "XMLAGG").split(",")));
    //</editor-fold>

    static {
        TABLE_ALIAS = new Alias("table_count");
        TABLE_ALIAS.setUseAs(false);
    }

    /**
     * 名字
     */
    private final String name;

    public CountModifier() {
        this("0");
    }

    public CountModifier(String name) {
        this.name = name;
    }

    @Override
    public String modify(Select select) {
        String sql = select.toString();
        // 特殊sql不需要去掉order by时，使用注释前缀
        if (sql.contains(KEEP_ORDERBY)) {
            return getSimpleCountSql(select.toString(), name);
        }
        SelectBody selectBody = select.getSelectBody();
        try {
            //处理body-去order by
            processSelectBody(selectBody);
        } catch (Exception e) {
            //当 sql 包含 group by 时，不去除 order by
            return getSimpleCountSql(sql, name);
        }
        //处理with-去order by
        processWithItemsList(select.getWithItemsList());
        //处理为count查询
        sqlToCount(select, name);
        return select.toString();
    }

    /**
     * 将sql转换为count查询
     *
     * @param select
     */
    public void sqlToCount(Select select, String name) {
        SelectBody selectBody = select.getSelectBody();
        // 是否能简化count查询
        List<SelectItem> COUNT_ITEM = new ArrayList<SelectItem>();
        COUNT_ITEM.add(new SelectExpressionItem(new Column("count(" + name + ")")));
        if (selectBody instanceof PlainSelect && isSimpleCount((PlainSelect) selectBody)) {
            ((PlainSelect) selectBody).setSelectItems(COUNT_ITEM);
        } else {
            PlainSelect plainSelect = new PlainSelect();
            SubSelect subSelect = new SubSelect();
            subSelect.setSelectBody(selectBody);
            subSelect.setAlias(TABLE_ALIAS);
            plainSelect.setFromItem(subSelect);
            plainSelect.setSelectItems(COUNT_ITEM);
            select.setSelectBody(plainSelect);
        }
    }

    /**
     * 是否可以用简单的count查询方式
     *
     * @param select
     * @return
     */
    public boolean isSimpleCount(PlainSelect select) {
        //包含group by的时候不可以
        if (select.getGroupBy() != null) {
            return false;
        }
        //包含distinct的时候不可以
        if (select.getDistinct() != null) {
            return false;
        }
        for (SelectItem item : select.getSelectItems()) {
            //select列中包含参数的时候不可以，否则会引起参数个数错误
            if (item.toString().contains("?")) {
                return false;
            }
            //如果查询列中包含函数，也不可以，函数可能会聚合列
            if (item instanceof SelectExpressionItem) {
                Expression expression = ((SelectExpressionItem) item).getExpression();
                if (expression instanceof Function) {
                    String name = ((Function) expression).getName();
                    if (name != null) {
                        String NAME = name.toUpperCase();
                        if (skipFunctions.contains(NAME)) {
                            //go on
                        } else if (falseFunctions.contains(NAME)) {
                            return false;
                        } else {
                            for (String aggregateFunction : AGGREGATE_FUNCTIONS) {
                                if (NAME.startsWith(aggregateFunction)) {
                                    falseFunctions.add(NAME);
                                    return false;
                                }
                            }
                            skipFunctions.add(NAME);
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * 处理selectBody去除Order by
     *
     * @param selectBody
     */
    public void processSelectBody(SelectBody selectBody) {
        if (selectBody instanceof PlainSelect) {
            processPlainSelect((PlainSelect) selectBody);
        } else if (selectBody instanceof WithItem) {
            WithItem withItem = (WithItem) selectBody;
            if (withItem.getSelectBody() != null) {
                processSelectBody(withItem.getSelectBody());
            }
        } else {
            SetOperationList operationList = (SetOperationList) selectBody;
            if (operationList.getSelects() != null && !operationList.getSelects().isEmpty()) {
                List<SelectBody> plainSelects = operationList.getSelects();
                for (SelectBody plainSelect : plainSelects) {
                    processSelectBody(plainSelect);
                }
            }
            if (!orderByHashParameters(operationList.getOrderByElements())) {
                operationList.setOrderByElements(null);
            }
        }
    }

    /**
     * 处理PlainSelect类型的selectBody
     *
     * @param plainSelect
     */
    public void processPlainSelect(PlainSelect plainSelect) {
        if (!orderByHashParameters(plainSelect.getOrderByElements())) {
            plainSelect.setOrderByElements(null);
        }
        if (plainSelect.getFromItem() != null) {
            processFromItem(plainSelect.getFromItem());
        }
        if (plainSelect.getJoins() != null && !plainSelect.getJoins().isEmpty()) {
            List<Join> joins = plainSelect.getJoins();
            for (Join join : joins) {
                if (join.getRightItem() != null) {
                    processFromItem(join.getRightItem());
                }
            }
        }
    }

    /**
     * 处理WithItem
     *
     * @param withItemsList
     */
    public void processWithItemsList(List<WithItem> withItemsList) {
        if (withItemsList != null && !withItemsList.isEmpty()) {
            for (WithItem item : withItemsList) {
                processSelectBody(item.getSelectBody());
            }
        }
    }

    /**
     * 处理子查询
     *
     * @param fromItem
     */
    public void processFromItem(FromItem fromItem) {
        if (fromItem instanceof SubJoin) {
            SubJoin subJoin = (SubJoin) fromItem;
            if (subJoin.getJoinList() != null && !subJoin.getJoinList().isEmpty()) {
                for (Join join : subJoin.getJoinList()) {
                    if (join.getRightItem() != null) {
                        processFromItem(join.getRightItem());
                    }
                }
            }
            if (subJoin.getLeft() != null) {
                processFromItem(subJoin.getLeft());
            }
        } else if (fromItem instanceof SubSelect) {
            SubSelect subSelect = (SubSelect) fromItem;
            if (subSelect.getSelectBody() != null) {
                processSelectBody(subSelect.getSelectBody());
            }
        } else if (fromItem instanceof ValuesList) {

        } else if (fromItem instanceof LateralSubSelect) {
            LateralSubSelect lateralSubSelect = (LateralSubSelect) fromItem;
            if (lateralSubSelect.getSubSelect() != null) {
                SubSelect subSelect = lateralSubSelect.getSubSelect();
                if (subSelect.getSelectBody() != null) {
                    processSelectBody(subSelect.getSelectBody());
                }
            }
        }
        //Table时不用处理
    }

    /**
     * 判断Orderby是否包含参数，有参数的不能去
     *
     * @param orderByElements
     * @return
     */
    public boolean orderByHashParameters(List<OrderByElement> orderByElements) {
        if (orderByElements == null) {
            return false;
        }
        for (OrderByElement orderByElement : orderByElements) {
            if (orderByElement.toString().contains("?")) {
                return true;
            }
        }
        return false;
    }

    public String getSimpleCountSql(final String sql, String name) {
        return "select count(" + name + ") from ( " + sql + " ) tmp_count";
    }
}
