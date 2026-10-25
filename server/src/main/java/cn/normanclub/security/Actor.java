package cn.normanclub.security;

import java.util.Arrays;
import static cn.normanclub.common.ApiException.require;

public record Actor(String id, String name, String role, String playerId, String tokenHash, String audience, boolean bearer) {
    public boolean staff() { return !role.equals("user"); }
    public void roles(String... allowed) { require(Arrays.asList(allowed).contains(role), 403, "当前账号没有此操作权限"); }
    public void user() { require(!staff(), 403, "请使用用户账号操作"); }
}
