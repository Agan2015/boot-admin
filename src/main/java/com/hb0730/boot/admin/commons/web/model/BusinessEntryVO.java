package com.hb0730.boot.admin.commons.web.model;

import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * <p>
 * 分录Vo
 * </p>
 *
 * @author bing_huang
 * @version V1.0
 * All rights Reserved, Designed By www.hohofast.com &#13;
 * 注意：本内容仅限于上海极吼吼信息技术有限公司内部传阅，禁止外泄以及用于其他的商业目 &#13;
 * @date 2019/8/28 10:00
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BusinessEntryVO extends BaseVO {

    private static final long serialVersionUID = 290384320276080238L;
    /**
     * 父类id
     */
    private String parentId;
    /**
     * 用于翻页 起始位置 (第几页)
     */
    protected Integer startRow = 1;
    /**
     * 用于翻页 每页记录数(每页显示数)
     */
    protected Integer pageSize = 10;
    /**
     * 用于排序 排序列(字段名称)
     */
    protected String sortDirection;
    /**
     * 用于多列排序(字段名称,字段名称)
     */
    protected String sortSql;
}
