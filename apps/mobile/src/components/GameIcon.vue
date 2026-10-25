<script setup lang="ts">
import { computed } from 'vue';
import delta from '../static/games/delta.png';
import valorant from '../static/games/valorant.png';
import lol from '../static/games/lol.png';

const props = withDefaults(defineProps<{ gameId: string; size?: number }>(), { size: 24 });
const icons: Record<string, string> = { delta, valorant, lol };
const source = computed(() => icons[props.gameId]);
</script>

<template>
  <view class="game-icon" :style="{ width: size + 'px', height: size + 'px' }" aria-hidden="true">
    <image v-if="source" class="game-icon-image" :src="source" mode="aspectFit" />
    <text v-else class="game-icon-fallback">✦</text>
  </view>
</template>

<style scoped>
.game-icon{display:flex;align-items:center;justify-content:center;flex-shrink:0}
.game-icon-image{display:block;width:100%;height:100%}
.game-icon-fallback{color:#a4a8b7;font-size:16px}
</style>
