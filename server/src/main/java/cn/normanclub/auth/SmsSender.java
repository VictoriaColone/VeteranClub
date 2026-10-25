package cn.normanclub.auth;

public interface SmsSender {
    void send(String phone,String code);
}
