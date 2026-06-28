package com.aidevplatform.customerservice.auth.application.result;

import java.util.List;

/**
 * Vben 路由节点 VO（替代 Map&lt;String, Object&gt;）。
 *
 * @author aidevplatform
 */
public class RouteVO {

    private final String name;
    private final String path;
    private final String component;
    private final String redirect;
    private final RouteMetaVO meta;
    private final List<RouteVO> children;

    public RouteVO(String name, String path, String component,
                   String redirect, RouteMetaVO meta, List<RouteVO> children) {
        this.name = name;
        this.path = path;
        this.component = component;
        this.redirect = redirect;
        this.meta = meta;
        this.children = children;
    }

    public String getName()           { return name; }
    public String getPath()           { return path; }
    public String getComponent()      { return component; }
    public String getRedirect()       { return redirect; }
    public RouteMetaVO getMeta()      { return meta; }
    public List<RouteVO> getChildren(){ return children; }
}
