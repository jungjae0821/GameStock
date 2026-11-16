import js from "@eslint/js";
import globals from "globals";
import reactHooks from "eslint-plugin-react-hooks";
import tseslint from "typescript-eslint";

/*
 * 웹(src)·E2E·테스트용 ESLint. 기존 코드를 한 번에 고치지 않도록 대부분의 규칙은 경고로 두고,
 * 실제 버그로 이어지는 규칙(훅 호출 순서 등)만 오류로 막는다. CI는 오류가 있을 때만 실패한다.
 */
export default tseslint.config(
  { ignores: ["dist", "node_modules", "mobile", "backend", "playwright-report", "test-results", ".claude", "anti-slop", "examples", "scripts"] },
  {
    files: ["**/*.{ts,tsx,js,mjs}"],
    extends: [js.configs.recommended, ...tseslint.configs.recommended],
    languageOptions: {
      ecmaVersion: 2022,
      globals: { ...globals.browser, ...globals.node },
    },
    plugins: { "react-hooks": reactHooks },
    rules: {
      // 훅을 조건문 안에서 부르면 렌더마다 상태가 엇갈린다. 바로 버그라 오류로 막는다.
      "react-hooks/rules-of-hooks": "error",
      "react-hooks/exhaustive-deps": "warn",
      // 아래는 기존 코드에 많이 걸려 있어 경고로 시작한다. 정리되면 하나씩 오류로 올린다.
      "@typescript-eslint/no-unused-vars": ["warn", { argsIgnorePattern: "^_", varsIgnorePattern: "^_" }],
      "@typescript-eslint/no-explicit-any": "warn",
      "@typescript-eslint/no-unused-expressions": "warn",
      "@typescript-eslint/no-empty-object-type": "warn",
      "no-empty": ["warn", { allowEmptyCatch: true }],
      "prefer-const": "warn",
      "no-useless-escape": "warn",
      "no-control-regex": "warn",
      "no-cond-assign": "warn",
      "no-case-declarations": "warn",
      "no-irregular-whitespace": "warn",
    },
  },
);
