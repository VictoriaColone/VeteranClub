export interface TouchPoint {clientX:number;clientY:number}

// Ignore finger jitter, then keep the first intended axis until touchend/cancel.
export function gestureAxis(origin:TouchPoint,current:TouchPoint,threshold=8):'x'|'y'|null {
  const x=Math.abs(current.clientX-origin.clientX),y=Math.abs(current.clientY-origin.clientY);
  return Math.max(x,y)<threshold?null:x>y?'x':'y';
}
