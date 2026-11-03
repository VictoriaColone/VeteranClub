package cn.normanclub.wallet;

import java.time.Clock;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import cn.normanclub.auth.*;
import cn.normanclub.business.*;
import cn.normanclub.business.Rows.*;
import cn.normanclub.common.*;
import cn.normanclub.security.*;
import static cn.normanclub.common.ApiException.require;
import static cn.normanclub.business.Rows.map;

/** All money mutations lock the member profile first. Orders lock order -> sorted profiles. */
@Service
public class WalletService {
    private final WalletMapper db;private final CatalogMapper profiles;private final AuthMapper users;private final UserIdentityMapper identities;
    private final LedgerMapper ledger;private final Crypto crypto;private final Clock clock;private final AuditService audit;private final AbuseService abuse;private final PasswordEncoder passwords;
    private final long delay;
    public WalletService(WalletMapper db,CatalogMapper profiles,AuthMapper users,UserIdentityMapper identities,LedgerMapper ledger,Crypto crypto,Clock clock,AuditService audit,AbuseService abuse,PasswordEncoder passwords,Environment env){
        this.db=db;this.profiles=profiles;this.users=users;this.identities=identities;this.ledger=ledger;this.crypto=crypto;this.clock=clock;this.audit=audit;this.abuse=abuse;this.passwords=passwords;
        long hours=env.getProperty("club.wallet-hold-hours",Long.class,24L);require(hours>=0&&hours<=720,500,"钱包结算等待时间配置无效");delay=hours*3_600_000L;
    }
    private Profile member(Actor a){a.user();require(a.playerId()!=null,403,"当前账号没有成员档案");Profile p=profiles.lockProfile(a.playerId());require(p!=null&&p.userId.equals(a.id()),403,"无权查看该钱包");var user=users.user(a.id());require(user!=null&&!user.disabled,403,"账号不可用");return p;}
    private void password(Actor a,String password){
        abuse.limit("wallet-password",a.audience()+":"+a.id(),10,300_000);
        String hash;if(a.staff()){var s=users.lockStaff(a.id());require(s!=null&&!s.disabled&&s.role.equals(a.role()),403,"管理账号不可用");hash=s.passwordHash;}
        else {var u=users.user(a.id());require(u!=null&&!u.disabled,403,"账号不可用");hash=users.latestUserPassword(a.id());}
        require(hash!=null&&passwords.matches(password,hash),403,"密码验证未通过；未设置密码请先到账号安全设置");
    }
    public static boolean bankNumber(String value){
        if(value==null||(!value.matches("[0-9]{16,19}")||value.chars().distinct().count()==1))return false;int total=0;boolean twice=false;
        for(int i=value.length()-1;i>=0;i--){int n=value.charAt(i)-'0';if(twice){n*=2;if(n>9)n-=9;}total+=n;twice=!twice;}return total%10==0;
    }
    private String plain(Inputs in,String name,int min,int max){String s=in.text(name,min,max);require(s.chars().noneMatch(Character::isISOControl),400,"字段包含无效字符");return s;}
    private Map<String,Object> bankView(WalletMapper.Bank b){return b==null?null:map("bankName",b.bankName,"lastFour",b.lastFour,"maskedHolder",ResidentIdentity.maskedName(crypto.decrypt(b.holderCipher)),"updatedAt",b.updatedAt,"verification","manual_required");}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> summary(Actor a){
        member(a);var b=db.balance(a.playerId(),clock.millis());
        return map("total",b.total,"pending",b.pending,"available",b.available,"frozen",b.frozen,"settled",b.paid,"simulation",b.simulation,"holdHours",delay/3_600_000L,"bank",bankView(db.bank(a.playerId())),"withdrawals",db.withdrawals(a.playerId()).stream().map(this::view).toList(),"entries",db.entries(a.playerId()));
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> saveBank(Actor a,Map<String,Object> body){
        Inputs in=new Inputs(body,"holder","bankName","bankNumber","password","consent");password(a,in.secret("password",1,72));member(a);
        require(in.bool("consent"),400,"请同意收款信息处理说明");var id=identities.get(a.id());require(id!=null&&id.identitySubmittedAt!=null,409,"请先填写实名认证信息");
        String holder=ResidentIdentity.name(plain(in,"holder",2,60));require(holder.equals(crypto.decrypt(id.realNameCipher)),400,"收款人姓名须与本人身份信息一致");
        String number=plain(in,"bankNumber",16,19);require(bankNumber(number),400,"银行卡号码格式或校验位无效");require(db.active(a.playerId())==0,409,"存在处理中提现，暂不能更换收款账户");
        WalletMapper.Bank bank=new WalletMapper.Bank();bank.playerId=a.playerId();bank.holderCipher=crypto.encrypt(holder);bank.numberCipher=crypto.encrypt(number);bank.bankName=plain(in,"bankName",2,80);bank.lastFour=number.substring(number.length()-4);bank.updatedAt=clock.millis();db.deleteBank(a.playerId());db.insertBank(bank);
        audit.record(a.name(),"更新收款银行卡",a.playerId(),"仅格式检查与填报姓名一致性检查，付款前需人工核对本人账户");return bankView(bank);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> withdraw(Actor a,Map<String,Object> body,String key){
        a.user();Inputs in=new Inputs(body,"amount","password");password(a,in.secret("password",1,72));Profile p=member(a);
        require(key!=null&&key.matches("[A-Za-z0-9_-]{8,80}"),400,"提现需要有效的 Idempotency-Key");long amount=in.integer("amount",100,5_000_000);
        var prior=db.request(a.id(),key);if(prior!=null){require(prior.amount==amount,409,"同一请求号不能用于不同提现金额");return view(prior);}
        abuse.limit("wallet-withdraw",a.id(),20,86_400_000);var bank=db.bank(a.playerId());require(bank!=null,409,"请先设置本人收款银行卡");
        require(db.balance(a.playerId(),clock.millis()).available>=amount,409,"可提现余额不足，待结算与模拟收入不能提现");
        WalletMapper.Withdrawal w=new WalletMapper.Withdrawal();w.id=Crypto.id("WD_");w.userId=a.id();w.playerId=p.id;w.playerName=p.name;w.requestKey=key;w.amount=amount;w.status="pending";w.holderCipher=bank.holderCipher;w.numberCipher=bank.numberCipher;w.bankName=bank.bankName;w.lastFour=bank.lastFour;w.createdAt=clock.millis();w.updatedAt=w.createdAt;db.insertWithdrawal(w);
        long left=amount;for(var i:db.available(p.id,clock.millis())){long part=Math.min(left,i.amount-i.frozen-i.paid);if(part<=0)continue;i.frozen+=part;db.updateIncome(i);db.allocate(w.id,i.incomeId,part);left-=part;if(left==0)break;}require(left==0,409,"钱包余额已更新，请重试");
        entry(p.id,"real","freeze",w.id,amount);audit.record(a.name(),"申请提现",w.id,amount+" 分；余额已冻结");return view(w);
    }
    public Map<String,Object> view(WalletMapper.Withdrawal w){return map("id",w.id,"playerId",w.playerId,"playerName",w.playerName,"amount",w.amount,"status",w.status,"bankName",w.bankName,"lastFour",w.lastFour,"maskedHolder",ResidentIdentity.maskedName(crypto.decrypt(w.holderCipher)),"reviewNote",w.reviewNote,"paymentReference",w.paymentReference,"paymentNote",w.paymentNote,"createdAt",w.createdAt,"updatedAt",w.updatedAt,"paidAt",w.paidAt);}
    public List<Map<String,Object>> list(Actor a){a.roles("admin","finance");return db.withdrawals(null).stream().map(this::view).toList();}
    private WalletMapper.Withdrawal locked(String id){var snapshot=db.withdrawal(Inputs.identifier(id));require(snapshot!=null,404,"提现记录不存在");require(profiles.lockProfile(snapshot.playerId)!=null,409,"成员档案缺失");return db.withdrawal(id);}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> cancel(Actor a,String id){a.user();var w=locked(id);require(w.userId.equals(a.id()),403,"无权撤销该提现");require(w.status.equals("pending"),409,"仅待审核提现可以撤销");release(w,"cancelled");audit.record(a.name(),"撤销提现",w.id,"余额已解冻");return view(w);}
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> details(Actor a,String id,Map<String,Object> body){
        a.roles("admin","finance");Inputs in=new Inputs(body,"password");password(a,in.secret("password",1,72));var w=locked(id);require(Set.of("approved","processing").contains(w.status),409,"审核通过后可核对付款账户");audit.record(a.name(),"查看提现付款账户",w.id,"敏感账户信息仅本次展示，不记入日志");return map("holder",crypto.decrypt(w.holderCipher),"bankNumber",crypto.decrypt(w.numberCipher),"bankName",w.bankName,"amount",w.amount);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED) public Map<String,Object> action(Actor a,String id,String action,Map<String,Object> body){
        a.roles("admin","finance");Inputs in=new Inputs(body,"note","password","reference","confirm");password(a,in.secret("password",1,72));var w=locked(id);String note=plain(in,"note",2,500);
        switch(action){
            case "approve" -> {if(w.status.equals("approved"))return view(w);require(w.status.equals("pending"),409,"提现已经审核");w.status="approved";w.reviewNote=note;}
            case "reject" -> {require(Set.of("pending","approved").contains(w.status),409,"已进入付款处理，须先核对实际打款结果");w.reviewNote=note;release(w,"rejected");}
            case "start" -> {if(w.status.equals("processing"))return view(w);require(w.status.equals("approved"),409,"请先审核提现");w.status="processing";w.paymentNote=note;}
            case "paid" -> {
                String reference=plain(in,"reference",4,100);require(in.bool("confirm"),400,"请确认已核对实际到账及银行流水");
                if(w.status.equals("paid")){require(reference.equals(w.paymentReference),409,"已登记不同的银行流水");return view(w);}
                require(w.status.equals("processing"),409,"请先进入付款处理");require(db.referenceOwner(reference)==null,409,"该银行流水已用于其他提现");
                for(var x:db.allocations(w.id)){var i=db.income(x.incomeId);require(i.frozen>=x.amount,409,"提现冻结记录不一致");i.frozen-=x.amount;i.paid+=x.amount;db.updateIncome(i);if(i.paid==i.amount)db.syncIncome(i.incomeId,(int)i.amount,"paid",clock.millis());}
                w.status="paid";w.paymentReference=reference;w.paymentNote=note;w.paidAt=clock.millis();entry(w.playerId,"real","paid",w.id,w.amount);
            }
            case "not-paid" -> {require(w.status.equals("processing")&&in.bool("confirm"),409,"需确认银行侧未付款，状态不明时不得解冻");w.paymentNote=note;release(w,"failed");}
            default -> throw new ApiException(404,"不支持的提现操作");
        }
        w.updatedAt=clock.millis();db.updateWithdrawal(w);audit.record(a.name(),"提现："+action,w.id,w.amount+" 分；"+w.status);return view(w);
    }
    private void release(WalletMapper.Withdrawal w,String status){for(var x:db.allocations(w.id)){var i=db.income(x.incomeId);require(i.frozen>=x.amount,409,"冻结余额异常");i.frozen-=x.amount;db.updateIncome(i);}w.status=status;w.updatedAt=clock.millis();db.updateWithdrawal(w);entry(w.playerId,"real","unfreeze",w.id,w.amount);}
    private void entry(String player,String mode,String kind,String reference,long amount){var e=new WalletMapper.Entry();e.id=Crypto.id("WE_");e.playerId=player;e.fundMode=mode;e.kind=kind;e.referenceId=reference;e.amount=amount;e.createdAt=clock.millis();db.entry(e);}
    /** No legacy backfill: only newly credited orders enter the new wallet. */
    public void credit(Order order,List<Member> members){
        members.stream().map(m->m.playerId).sorted().forEach(profiles::lockProfile);
        var payment=ledger.payment(order.paymentId);String mode=payment!=null&&"wechat".equals(payment.channel)&&"paid".equals(payment.status)?"real":"simulation";
        for(var m:members){var income=ledger.income(order.id,m.playerId);if(income==null||db.income(income.id)!=null)continue;
            var i=new WalletMapper.Income();i.incomeId=income.id;i.orderId=order.id;i.playerId=m.playerId;i.fundMode=mode;i.amount=income.amount;i.releaseAt=clock.millis()+delay;db.insertIncome(i);entry(m.playerId,mode,"credit",income.id,i.amount);
        }
    }
    public boolean refundable(Order o){var incomes=db.orderIncome(o.id);incomes.stream().map(i->i.playerId).distinct().sorted().forEach(profiles::lockProfile);return !incomes.isEmpty()&&db.orderIncome(o.id).stream().allMatch(i->i.paid==0&&i.frozen==0);}
    public void refundState(Order o,OrderMapper orders){
        var income=db.orderIncome(o.id);income.stream().map(i->i.playerId).distinct().sorted().forEach(profiles::lockProfile);
        var members=orders.members(o.id);
        for(var i:db.orderIncome(o.id)){i.blocked=Set.of("pending","processing").contains(o.refundStatus);
            if("refunded".equals(o.refundStatus)){require(i.paid==0&&i.frozen==0,409,"存在已提现资金，须先核对退款冲账");int index=0;while(index<members.size()&&!members.get(index).playerId.equals(i.playerId))index++;require(index<members.size(),409,"订单成员缺失");long amount=CommissionAmounts.share(o,members,index);long delta=amount-i.amount;i.amount=amount;db.syncIncome(i.incomeId,(int)amount,amount==0?"paid":"credited",amount==0?clock.millis():null);if(delta!=0)entry(i.playerId,i.fundMode,"refund",i.incomeId,delta);}
            db.updateIncome(i);
        }
    }
}
