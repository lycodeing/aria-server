package com.aidevplatform.customerservice.auth.application.result;

/**
 * Vben 路由 meta 信息 VO。
 *
 * @author aidevplatform
 */
public class RouteMetaVO {

    private final String  title;
    private final String  icon;
    private final Boolean keepAlive;
    private final Boolean hideInMenu;
    private final String  link;
    private final Integer order;

    public RouteMetaVO(String title, String icon, Boolean keepAlive,
                       Boolean hideInMenu, String link, Integer order) {
        this.title      = title;
        this.icon       = icon;
        this.keepAlive  = keepAlive;
        this.hideInMenu = hideInMenu;
        this.link       = link;
        this.order      = order;
    }

    public String  getTitle()      { return title; }
    public String  getIcon()       { return icon; }
    public Boolean getKeepAlive()  { return keepAlive; }
    public Boolean getHideInMenu() { return hideInMenu; }
    public String  getLink()       { return link; }
    public Integer getOrder()      { return order; }
}
