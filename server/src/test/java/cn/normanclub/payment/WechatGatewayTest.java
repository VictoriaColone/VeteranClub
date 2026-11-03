package cn.normanclub.payment;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import cn.normanclub.common.ApiException;
import cn.normanclub.config.ClubConfig;
import cn.normanclub.security.Crypto;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WechatGatewayTest {
    @TempDir Path root;KeyPair platform;WechatGateway gateway;
    final Clock clock=Clock.fixed(Instant.parse("2026-11-02T01:00:00Z"),ZoneOffset.UTC);
    final JsonMapper json=JsonMapper.builder().build();final String apiKey="0123456789abcdef0123456789abcdef";
    MockEnvironment base(){return new MockEnvironment().withProperty("club.encryption-key","AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=").withProperty("club.hash-key","AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=").withProperty("club.allowed-origins","https://payments.example");}
    String pem(String name,String type,byte[] bytes)throws Exception{Path p=root.resolve(name);Files.writeString(p,"-----BEGIN "+type+"-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(bytes)+"\n-----END "+type+"-----\n");return p.toString();}
    void configured()throws Exception{
        var gen=KeyPairGenerator.getInstance("RSA");gen.initialize(2048);platform=gen.generateKeyPair();var merchant=gen.generateKeyPair();Path key=root.resolve("api-key"),secret=root.resolve("app-secret");Files.writeString(key,apiKey);Files.writeString(secret,"fixtureAppSecret123456789");
        var env=base().withProperty("club.wechat.enabled","true").withProperty("club.wechat.merchant-id","1234567890").withProperty("club.wechat.app-id","wx1234567890123456").withProperty("club.wechat.serial","A".repeat(40)).withProperty("club.wechat.origin","https://payments.example").withProperty("club.wechat.public-key-id","PUB_KEY_ID_FIXTURE").withProperty("club.wechat.private-key-path",pem("merchant.pem","PRIVATE KEY",merchant.getPrivate().getEncoded())).withProperty("club.wechat.public-key-path",pem("platform.pem","PUBLIC KEY",platform.getPublic().getEncoded())).withProperty("club.wechat.api-key-file",key.toString()).withProperty("club.wechat.app-secret-file",secret.toString());
        gateway=new WechatGateway(new WechatConfig(env,new ClubConfig(env)),mock(WechatIdentityMapper.class),mock(cn.normanclub.business.OrderMapper.class),mock(Crypto.class),clock,json);
    }
    byte[] payload(String app)throws Exception{
        String plain=json.writeValueAsString(Map.of("appid",app,"mchid","1234567890","out_trade_no","a".repeat(32),"transaction_id","fixture-txn","trade_state","SUCCESS","trade_type","JSAPI","amount",Map.of("total",100,"currency","CNY")));
        String nonce="0123456789ab",aad="transaction";var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(apiKey.getBytes(StandardCharsets.UTF_8),"AES"),new GCMParameterSpec(128,nonce.getBytes(StandardCharsets.UTF_8)));cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));String encrypted=Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        return json.writeValueAsBytes(Map.of("id","fixture-event","event_type","TRANSACTION.SUCCESS","resource_type","encrypt-resource","resource",Map.of("algorithm","AEAD_AES_256_GCM","ciphertext",encrypted,"associated_data",aad,"nonce",nonce,"original_type","transaction")));
    }
    Map<String,String> signed(byte[] body,long timestamp)throws Exception{String nonce="fixture-notification";var signature=Signature.getInstance("SHA256withRSA");signature.initSign(platform.getPrivate());signature.update((timestamp+"\n"+nonce+"\n"+new String(body,StandardCharsets.UTF_8)+"\n").getBytes(StandardCharsets.UTF_8));return Map.of("wechatpay-timestamp",Long.toString(timestamp),"wechatpay-nonce",nonce,"wechatpay-serial","PUB_KEY_ID_FIXTURE","wechatpay-signature",Base64.getEncoder().encodeToString(signature.sign()));}
    @Test void disabledConfigurationDoesNotReadKeysOrAcceptPayments(){var env=base();var config=new WechatConfig(env,new ClubConfig(env));assertFalse(config.enabled);var disabled=new WechatGateway(config,null,null,null,clock,json);assertEquals(503,assertThrows(ApiException.class,()->disabled.verify(Map.of(),new byte[]{1})).status);}
    @Test void officialSdkVerifiesSignatureAndDecryptsNotificationBeforeCrediting()throws Exception{configured();var body=payload("wx1234567890123456");var headers=signed(body,clock.instant().getEpochSecond());var verified=gateway.verify(headers,body);assertEquals(100,verified.amount());assertEquals("fixture-event",verified.eventId());assertEquals("wechat",verified.channel());body[body.length-2]^=1;assertEquals(400,assertThrows(ApiException.class,()->gateway.verify(headers,body)).status);}
    @Test void staleOrWrongAppNotificationIsRejectedEvenWhenSigned()throws Exception{configured();var body=payload("wx9999999999999999");assertThrows(ApiException.class,()->gateway.verify(signed(body,clock.instant().getEpochSecond()),body));var good=payload("wx1234567890123456");assertThrows(ApiException.class,()->gateway.verify(signed(good,clock.instant().getEpochSecond()-301),good));}
}
