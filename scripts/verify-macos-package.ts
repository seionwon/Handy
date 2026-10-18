// Validate the expanded shipping archive, including Installer's close-before-
// copy rule. Checking only our source XML would miss changes made by pkgbuild
// or productbuild. This command never installs the package or launches apps.
import { join } from "node:path";
import { execFileSync } from "node:child_process";

const [expanded, version, architectures] = process.argv.slice(2);
if (
  !expanded ||
  !/^\d+\.\d+\.\d+$/.test(version ?? "") ||
  !["arm64", "arm64,x86_64"].includes(architectures)
) {
  throw new Error(
    "Usage: bun scripts/verify-macos-package.ts <expanded> <version> <architectures>",
  );
}

function requireXml(file: string, condition: string) {
  const result = execFileSync(
    "/usr/bin/xmllint",
    ["--xpath", `boolean(${condition})`, file],
    { encoding: "utf8" },
  ).trim();
  if (result !== "true") {
    throw new Error(`Invalid installer metadata: ${condition}`);
  }
}

const distribution = join(expanded, "Distribution");
requireXml(
  distribution,
  "count(//must-close/app) = 1 and //pkg-ref[@id='com.pais.handy.pkg']/must-close/app[@id='com.pais.handy']",
);
requireXml(
  distribution,
  `//pkg-ref[@id='com.pais.handy.pkg'][@version='${version}'][@onConclusion='None'][normalize-space(text())='Handy.pkg' or normalize-space(text())='#Handy.pkg']`,
);
requireXml(
  distribution,
  "//domains[@enable_localSystem='true'][@enable_currentUserHome='false'][@enable_anywhere='false']",
);
requireXml(
  distribution,
  `//options[@require-scripts='false'][@customize='never'][@rootVolumeOnly='true'][@hostArchitectures='${architectures}']`,
);
requireXml(
  distribution,
  "//volume-check/allowed-os-versions/os-version[@min='11.0']",
);
requireXml(distribution, "not(//script) and not(//relocate)");

const component = join(expanded, "Handy.pkg");
const info = join(component, "PackageInfo");
requireXml(
  info,
  `/pkg-info[@identifier='com.pais.handy.pkg'][@version='${version}'][@install-location='/'][@relocatable='false'][@postinstall-action='none']`,
);
requireXml(info, "not(//scripts) and not(//relocate/*)");
requireXml(
  info,
  "count(/pkg-info/bundle) = 1 and /pkg-info/bundle[@id='com.pais.handy'][@path='./Applications/Handy.app']",
);
requireXml(
  info,
  "count(/pkg-info/upgrade-bundle/bundle) = 1 and /pkg-info/upgrade-bundle/bundle[@id='com.pais.handy'] and not(/pkg-info/update-bundle/*)",
);
requireXml(
  info,
  "count(/pkg-info/strict-identifier/bundle) = 1 and /pkg-info/strict-identifier/bundle[@id='com.pais.handy']",
);
requireXml(info, "not(/pkg-info/bundle-version/*)");

const app = join(component, "Payload/Applications/Handy.app");
const plist = JSON.parse(
  execFileSync(
    "/usr/bin/plutil",
    ["-convert", "json", "-o", "-", join(app, "Contents/Info.plist")],
    { encoding: "utf8" },
  ),
);
if (
  plist.CFBundleIdentifier !== "com.pais.handy" ||
  plist.CFBundleShortVersionString !== version
) {
  throw new Error(
    "The archived app does not match the installer identity/version.",
  );
}
execFileSync("/usr/bin/lipo", [
  join(app, "Contents/MacOS/handy"),
  "-verify_arch",
  ...architectures.split(","),
]);
console.log(
  "Verified native close-before-copy requirement, fixed install location, script-free archive and app identity/version/architectures.",
);
