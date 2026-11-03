package cn.normanclub.auth;

import java.time.Clock;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.normanclub.business.AuditService;
import cn.normanclub.business.CatalogMapper;
import cn.normanclub.common.*;
import cn.normanclub.security.*;
import static cn.normanclub.common.ApiException.require;
import static cn.normanclub.business.Rows.map;

@Service
public class UserIdentityService {
    public static final String CONSENT_VERSION="2026-10-31";
    private final UserIdentityMapper db;private final AuthMapper users;private final Crypto crypto;private final Clock clock;private final IdentityBitmap bitmap;private final AbuseService abuse;private final AuditService audit;private final CatalogMapper catalog;
    public UserIdentityService(UserIdentityMapper db,AuthMapper users,Crypto crypto,Clock clock,IdentityBitmap bitmap,AbuseService abuse,AuditService audit,CatalogMapper catalog){this.db=db;this.users=users;this.crypto=crypto;this.clock=clock;this.bitmap=bitmap;this.abuse=abuse;this.audit=audit;this.catalog=catalog;}
    public Map<String,Object> current(Actor actor){actor.user();return view(db.get(actor.id()),actor.id());}
    private Map<String,Object> view(UserIdentityMapper.Entry entry,String userId){
        boolean submitted=entry!=null&&entry.identitySubmittedAt!=null;
        String number=submitted?crypto.decrypt(entry.idNumberCipher):null;
        var profile=catalog.byUser(userId);boolean onboarded=profile!=null&&profile.approved&&!catalog.qualifications(profile.id).isEmpty();
        boolean adult=submitted&&ResidentIdentity.adult(number,clock);
        return map("status",submitted?"self_declared":"missing","adult",adult,"onboarded",onboarded,"canOrder",adult||onboarded,"maskedName",submitted?ResidentIdentity.maskedName(crypto.decrypt(entry.realNameCipher)):"","maskedIdNumber",submitted?ResidentIdentity.maskedNumber(number):"","submittedAt",submitted?entry.identitySubmittedAt:null,"consentVersion",CONSENT_VERSION,"verification","not_verified");
    }
    public void requireOrdering(Actor actor){
        actor.user();var user=users.user(actor.id());require(user!=null&&!user.disabled,403,"当前账号暂不可用");
        var status=current(actor);require(Boolean.TRUE.equals(status.get("canOrder")),403,"missing".equals(status.get("status"))?"未完成年龄登记，请前往我的 → 实名认证填写身份信息":"根据填写的证件信息，您未满18周岁且尚未入驻，暂不能下单");
    }
    @Transactional public Map<String,Object> submit(Actor actor,Map<String,Object> body){
        actor.user();abuse.limit("identity-submit",actor.id(),10,86_400_000);
        Inputs in=new Inputs(body,"name","idNumber","consent","consentVersion");
        require(in.bool("consent")&&CONSENT_VERSION.equals(in.text("consentVersion",1,24)),400,"请阅读并同意身份信息处理说明");
        String name=ResidentIdentity.name(in.text("name",2,60)),number=ResidentIdentity.number(in.text("idNumber",18,18),clock);
        require(!ResidentIdentity.birth(number).plusYears(14).isAfter(ResidentIdentity.today(clock)),403,"暂不受理未满14周岁用户的身份信息，填写内容未保存");
        var user=users.lockUser(actor.id());require(user!=null&&!user.disabled,403,"当前账号暂不可用");
        var previous=db.get(actor.id());require(previous.identitySubmittedAt==null,409,"身份信息已提交，修改请联系平台客服");
        String hash=crypto.digest("resident-id:"+number);
        if(bitmap.mightContain(hash))require(db.owner(hash)==null,409,"该证件已绑定其他账号，请使用原账号或联系客服");
        UserIdentityMapper.Entry entry=new UserIdentityMapper.Entry();entry.id=actor.id();entry.realNameCipher=crypto.encrypt(name);entry.idNumberCipher=crypto.encrypt(number);entry.idNumberHash=hash;entry.identitySubmittedAt=clock.millis();entry.identityConsentVersion=CONSENT_VERSION;
        try{require(db.submit(entry)==1,409,"身份信息已更新，请刷新后重试");}
        catch(DuplicateKeyException e){throw new ApiException(409,"该证件已绑定其他账号，请使用原账号或联系客服");}
        bitmap.add(hash);audit.record(actor.name(),"提交身份信息",actor.id(),"用户填报；未联网核验");return view(entry,actor.id());
    }
}
