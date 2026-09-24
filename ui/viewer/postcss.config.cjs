// Panda CSS の PostCSS プラグイン。src/index.css の @layer 宣言に、panda.config.ts から生成した CSS を差し込む。
// vite.config.ts に直接書くと、Vite と Panda が別々の postcss の型を持っていて型検査が通らないため、ここに置く
module.exports = {
  plugins: {
    "@pandacss/dev/postcss": {},
  },
};
