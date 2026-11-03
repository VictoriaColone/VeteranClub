<script setup lang="ts">
import {ref} from 'vue';
import {onLoad} from '@dcloudio/uni-app';
import ClientPage from '../index/index.vue';
const initialStatus=ref('all');
onLoad(options=>{initialStatus.value=options?.status||'all';});
</script>
<template><ClientPage personal-view="orders" :initial-status="initialStatus"/></template>
