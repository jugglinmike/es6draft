// Replace definition from harness/detachArrayBuffer.js
function $DETACHBUFFER(buffer) {
  ArrayBuffer.transfer(buffer);
}
