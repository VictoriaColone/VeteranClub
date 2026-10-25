package cn.normanclub.common;

import java.util.*;
import static cn.normanclub.common.ApiException.require;

public final class Inputs {
    private final Map<String,Object> fields;
    public Inputs(Map<String,Object> fields, String... allowed) {
        this.fields = fields == null ? Map.of() : fields;
        require(Set.of(allowed).containsAll(this.fields.keySet()), 400, "含有不支持的参数");
    }
    public String text(String key, int min, int max) {
        Object value = fields.get(key);
        require(value instanceof String, 400, key + " 必须是字符串");
        String s = ((String)value).trim();
        require(s.length() >= min && s.length() <= max && !s.contains("\u0000"), 400, key + " 长度不正确");
        return s;
    }
    public String optional(String key, int max) { return fields.get(key) == null ? "" : text(key, 0, max); }
    public String secret(String key,int min,int max){Object value=fields.get(key);require(value instanceof String s&&s.length()>=min&&s.length()<=max,400,"凭据格式不正确");return (String)value;}
    public int integer(String key, int min, int max) {
        Object value = fields.get(key);
        require(value instanceof Integer || value instanceof Long, 400, key + " 必须为整数");
        long n = ((Number)value).longValue();
        require(n >= min && n <= max, 400, key + " 超出有效范围"); return (int)n;
    }
    public boolean bool(String key) {
        require(fields.get(key) instanceof Boolean, 400, key + " 必须为布尔值"); return (Boolean)fields.get(key);
    }
    public boolean has(String key) { return fields.containsKey(key); }
    public List<String> strings(String key, int max) {
        Object value = fields.get(key);
        require(value instanceof List<?> list && !list.isEmpty() && list.size() <= max, 400, key + " 列表无效");
        List<?> list = (List<?>)value;
        require(list.stream().allMatch(v -> v instanceof String s && s.length() <= 32), 400, key + " 列表无效");
        return list.stream().map(v -> (String)v).distinct().toList();
    }
    public static String identifier(String value) {
        require(value != null && value.matches("[A-Za-z0-9_-]{1,80}"), 400, "标识格式不正确"); return value;
    }
    public static String like(String value) {
        require(value != null && value.length() <= 100, 400, "搜索内容过长");
        return "%" + value.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }
}
