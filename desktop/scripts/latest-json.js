// Writes dist/latest.json for Atlas's own update check on the PC (renderer/selfwire.js): the version, its notes from
// CHANGELOG.json, where the installer is, and its SHA-256 (checked before installing). Run by desktop.yml after the build.
'use strict';
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const here = path.join(__dirname, '..');
const v = require(path.join(here, 'package.json')).version;
const buf = fs.readFileSync(path.join(here, 'dist', 'Atlas-Setup.exe'));
const cl = JSON.parse(fs.readFileSync(path.join(here, '..', 'CHANGELOG.json'), 'utf8'));
const e = (cl.versions || []).find((x) => x.version === v) || {};
const out = {
  version: v, versionCode: e.versionCode || 0,
  url: 'https://github.com/anjystar10/atlas/releases/download/desktop/Atlas-Setup.exe',
  sha256: crypto.createHash('sha256').update(buf).digest('hex'), size: buf.length,
  notes: e.notes || [], builtFrom: process.env.GITHUB_SHA || '',
};
fs.writeFileSync(path.join(here, 'dist', 'latest.json'), JSON.stringify(out, null, 1));
console.log(JSON.stringify(out, null, 1));
