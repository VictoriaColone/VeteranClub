<script setup lang="ts">
import {computed,ref,onMounted,onUnmounted,watch} from 'vue';
import {ElMessage,ElMessageBox} from 'element-plus';
import {api,useStaff} from '../store';
import {money,time,withdrawalLabels,type Withdrawal} from '@stellar/core';
const staff=useStaff(),rows=ref<Withdrawal[]>([]),loading=ref(false),busy=ref(false),error=ref(''),query=ref(''),status=ref('all'),dialog=ref(false),selected=ref<Withdrawal|null>(null),action=ref('approve'),note=ref(''),password=ref(''),reference=ref(''),confirmed=ref(false);
const bank=ref<{holder:string;bankName:string;bankNumber:string;amount:number}|null>(null);
const filtered=computed(()=>rows.value.filter(w=>(status.value==='all'||w.status===status.value)&&[w.id,w.playerName,w.playerId,w.lastFour,w.paymentReference||''].join(' ').toLowerCase().includes(query.value.trim().toLowerCase())));
const totals=computed(()=>({pending:rows.value.filter(w=>w.status==='pending').length,approved:rows.value.filter(w=>w.status==='approved').reduce((n,w)=>n+w.amount,0),processing:rows.value.filter(w=>w.status==='processing').reduce((n,w)=>n+w.amount,0)}));
let timer:ReturnType<typeof setTimeout>|undefined,alive=true,version=0;
async function load(){if(loading.value)return;loading.value=true;const owner=staff.session;try{const value=await api.withdrawals();if(alive&&staff.session===owner){rows.value=value;error.value='';}}catch(e){error.value=e instanceof Error?e.message:'提现记录加载失败';}finally{loading.value=false;}}
function schedule(){clearTimeout(timer);if(alive)timer=setTimeout(async()=>{if(!document.hidden)await load();schedule();},30000);}
const titles:Record<string,string>={approve:'审核通过',reject:'拒绝提现',start:'进入人工付款',paid:'登记已付款','not-paid':'确认银行侧未付款',bank:'核对收款账户'};
function clear(){password.value='';note.value='';reference.value='';confirmed.value=false;bank.value=null;version++;}
function open(w:Withdrawal,next:string){clear();selected.value=w;action.value=next;dialog.value=true;}
async function submit(){if(busy.value||!selected.value)return;busy.value=true;const round=version,owner=staff.session;
 try{if(action.value==='bank'){const value=await api.withdrawalBank(selected.value.id,password.value);if(round===version&&staff.session===owner)bank.value=value;}
 else{await api.withdrawalAction(selected.value.id,action.value,{note:note.value,password:password.value,...(action.value==='paid'?{reference:reference.value,confirm:confirmed.value}:action.value==='not-paid'?{confirm:confirmed.value}:{})});if(round===version&&staff.session===owner){dialog.value=false;clear();ElMessage.success('提现状态已更新');await load();}}
 }catch(e){ElMessage.error(e instanceof Error?e.message:'操作失败');}finally{password.value='';busy.value=false;}
}
watch(dialog,visible=>{if(!visible)clear();});watch(()=>staff.session,()=>{rows.value=[];dialog.value=false;clear();});
onMounted(()=>{void load();schedule();});onUnmounted(()=>{alive=false;clearTimeout(timer);clear();});
function beforeClose(done:()=>void){if(!busy.value)done();}
</script>
<template>
 <div class="withdrawal-desk">
  <div class="summary-strip"><div><span>待审核申请</span><strong>{{totals.pending}}</strong></div><div><span>审核通过待付款</span><strong>¥{{money(totals.approved)}}</strong></div><div><span>付款处理中</span><strong>¥{{money(totals.processing)}}</strong></div></div>
  <el-alert title="这里仅管理真实钱包提现。平台不会自动划款；请先核对本人收款账户，再通过经营结算账户人工付款。银行结果不明时保持处理中。" type="info" :closable="false" show-icon/>
  <section class="panel"><div class="withdrawal-tools"><el-input v-model="query" clearable placeholder="搜索提现号、成员、银行卡尾号或流水" aria-label="搜索提现"/><el-select v-model="status" aria-label="提现状态"><el-option label="全部状态" value="all"/><el-option v-for="(label,id) in withdrawalLabels" :key="id" :label="label" :value="id"/></el-select><el-button :loading="loading" @click="load">刷新</el-button></div>
   <el-alert v-if="error" :title="error" type="error" :closable="false"/>
   <el-table :data="filtered" v-loading="loading" empty-text="暂无真实提现申请"><el-table-column label="提现申请" min-width="210"><template #default="{row}"><strong>{{row.playerName}}</strong><div class="cell-sub">{{row.id}}</div><div class="cell-sub">{{time(row.createdAt)}}</div></template></el-table-column><el-table-column label="申请金额" width="130"><template #default="{row}"><strong>¥{{money(row.amount)}}</strong></template></el-table-column><el-table-column label="收款账户" min-width="150"><template #default="{row}">{{row.bankName}}<div class="cell-sub">{{row.maskedHolder}} · 尾号{{row.lastFour}}</div></template></el-table-column><el-table-column label="状态 / 说明" min-width="190"><template #default="{row}"><el-tag :type="row.status==='paid'?'success':row.status==='processing'?'warning':'info'">{{withdrawalLabels[row.status as Withdrawal['status']]}}</el-tag><div v-if="row.reviewNote" class="cell-sub">{{row.reviewNote}}</div><div v-if="row.paymentReference" class="cell-sub">流水：{{row.paymentReference}}</div><div v-if="row.paymentNote" class="cell-sub">{{row.paymentNote}}</div></template></el-table-column><el-table-column label="操作" min-width="240" fixed="right"><template #default="{row}"><el-button v-if="row.status==='pending'" link type="primary" :disabled="busy" @click="open(row,'approve')">审核通过</el-button><el-button v-if="['pending','approved'].includes(row.status)" link type="danger" :disabled="busy" @click="open(row,'reject')">拒绝</el-button><el-button v-if="['approved','processing'].includes(row.status)" link :disabled="busy" @click="open(row,'bank')">核对账户</el-button><el-button v-if="row.status==='approved'" link type="primary" :disabled="busy" @click="open(row,'start')">进入付款</el-button><template v-if="row.status==='processing'"><el-button link type="primary" :disabled="busy" @click="open(row,'paid')">登记已付款</el-button><el-button link type="danger" :disabled="busy" @click="open(row,'not-paid')">确认未付款</el-button></template></template></el-table-column></el-table><div class="panel-footnote">最近200笔申请。付款流水不能重复登记；所有资金状态变更、敏感账户查看均记录审计日志。</div>
  </section>
  <el-dialog v-model="dialog" :title="titles[action]" width="500px" :close-on-click-modal="false" :before-close="beforeClose">
   <div class="withdrawal-amount">{{selected?.playerName}}<strong>¥{{money(selected?.amount||0)}}</strong></div>
   <el-alert v-if="action==='start'" title="提交后只标记付款处理中，不会调用银行转账。请核对账户后由你人工操作付款。" type="warning" :closable="false"/>
   <el-alert v-if="action==='not-paid'" title="只有确认银行未付款才能解冻。若正在转账、结果未知或到账延迟，请关闭此窗口并保持处理中，防止重复出款。" type="error" :closable="false"/>
   <div v-if="bank" class="withdrawal-bank"><p>收款人：{{bank.holder}}</p><p>银行：{{bank.bankName}}</p><p>账号：{{bank.bankNumber}}</p><small>当前仅做填报姓名一致性与格式检查，请人工核对为本人账户。关闭后不再保留明文。</small></div>
   <el-form label-position="top"><el-form-item v-if="action!=='bank'" :label="action==='paid'?'付款核对说明':'处理说明'" required><el-input v-model="note" type="textarea" :maxlength="500" :rows="3" :disabled="busy"/></el-form-item><el-form-item v-if="action==='paid'" label="实际转账银行流水号" required><el-input v-model="reference" :maxlength="100" :disabled="busy" placeholder="填写真实流水，不使用提现申请号代替"/></el-form-item><el-checkbox v-if="['paid','not-paid'].includes(action)" v-model="confirmed" :disabled="busy">{{action==='paid'?'我已核对实际付款金额、收款人和成功银行流水':'我已核对银行侧确认未付款，同意解冻该笔金额'}}</el-checkbox><el-form-item label="当前管理员登录密码" required><el-input v-model="password" type="password" show-password autocomplete="off" :maxlength="72" :disabled="busy"/></el-form-item></el-form>
   <template #footer><el-button :disabled="busy" @click="dialog=false">返回</el-button><el-button type="primary" :loading="busy" :disabled="!password||(action!=='bank'&&note.trim().length<2)||(['paid','not-paid'].includes(action)&&!confirmed)" @click="submit">{{action==='bank'?'验证并显示账户':titles[action]}}</el-button></template>
  </el-dialog>
 </div>
</template>
<style scoped>
.withdrawal-desk>.el-alert{margin-bottom:20px}.withdrawal-tools{display:flex;gap:12px;padding:20px;flex-wrap:wrap}.withdrawal-tools>.el-input{max-width:380px}.withdrawal-tools>.el-select{width:160px}.withdrawal-amount{display:flex;justify-content:space-between;align-items:center;margin-bottom:22px;font-size:16px}.withdrawal-amount strong{font-size:26px}.withdrawal-desk :deep(.el-form){margin-top:20px}.withdrawal-bank{padding:16px;border:1px solid #41495c;border-radius:10px;margin:16px 0;overflow-wrap:anywhere}.withdrawal-bank small{color:#989eae;line-height:1.8}.withdrawal-desk :deep(.el-checkbox){white-space:normal;height:auto;margin-bottom:16px}
</style>
