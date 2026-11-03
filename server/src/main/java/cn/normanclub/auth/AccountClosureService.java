package cn.normanclub.auth;

import java.time.Clock;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import cn.normanclub.business.*;
import cn.normanclub.common.Inputs;
import cn.normanclub.security.*;
import static cn.normanclub.common.ApiException.require;

@Service
public class AccountClosureService {
    private final AuthMapper users;private final AuthService auth;private final CatalogMapper catalog;private final AccountClosureMapper db;private final Crypto crypto;private final Clock clock;private final AuditService audit;private final PresenceUpdates presence;
    public AccountClosureService(AuthMapper users,AuthService auth,CatalogMapper catalog,AccountClosureMapper db,Crypto crypto,Clock clock,AuditService audit,PresenceUpdates presence){this.users=users;this.auth=auth;this.catalog=catalog;this.db=db;this.crypto=crypto;this.clock=clock;this.audit=audit;this.presence=presence;}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED,noRollbackFor=cn.normanclub.common.ApiException.class)
    public void close(Actor actor,Map<String,Object> body,String ip){
        actor.user();Inputs in=new Inputs(body,"code","confirm");require(in.bool("confirm"),400,"请确认注销账号");
        var phone=users.phoneByUser(actor.id());require(phone!=null,403,"体验账号不支持注销，请联系平台");
        auth.consumeCode(crypto.decrypt(phone.subjectCipher),in.text("code",6,6),ip,"close");
        var user=users.lockUser(actor.id());require(user!=null&&!user.disabled,403,"当前账号暂不可用");
        var profile=catalog.byUser(actor.id());if(profile!=null)profile=catalog.lockProfile(profile.id);
        String playerId=profile==null?null:profile.id;
        require(db.openOrders(actor.id(),playerId)==0&&db.openPayments(actor.id())==0,409,"请先处理未完成订单、售后或待确认支付，再申请注销");
        require(db.unpaidIncome(playerId)==0&&db.withdrawals(playerId)==0,409,"还有未结清收益，请联系平台处理后注销");
        require(db.close(actor.id(),clock.millis())==1,409,"账号状态已更新，请重新登录确认");
        users.revoke(actor.id(),"user");db.identities(actor.id());db.wechatIdentity(actor.id());db.wechatState(actor.id());db.bank(playerId);db.profile(actor.id());db.products(actor.id());db.qualifications(playerId);db.applications(actor.id(),clock.millis());
        audit.record(actor.name(),"注销用户账号",actor.id(),"短信验证成功；释放手机号及证件绑定，历史交易记录保留");presence.changed();
    }
}
