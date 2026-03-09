/**
 * Build script for the Burner Wallet Chrome extension.
 *
 * Uses esbuild to bundle TypeScript + npm dependencies into self-contained
 * JS files suitable for Chrome extension Manifest V3.
 */

import * as esbuild from "esbuild";
import { copyFileSync, mkdirSync } from "fs";

mkdirSync("dist", { recursive: true });

// Bundle popup.ts -> dist/popup.js (includes all npm dependencies)
await esbuild.build({
  entryPoints: ["src/popup.ts"],
  bundle: true,
  outfile: "dist/popup.js",
  format: "iife",
  target: "chrome110",
  minify: false,
  sourcemap: false,
});

// Bundle background.ts -> dist/background.js
await esbuild.build({
  entryPoints: ["src/background.ts"],
  bundle: true,
  outfile: "dist/background.js",
  format: "iife",
  target: "chrome110",
  minify: false,
  sourcemap: false,
});

// Copy static assets
copyFileSync("manifest.json", "dist/manifest.json");
copyFileSync("src/popup.html", "dist/popup.html");
copyFileSync("src/popup.css", "dist/popup.css");

console.log("Build complete -> dist/");
