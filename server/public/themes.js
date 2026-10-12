// Named color themes, ported from ui/theme/AppThemes.kt. Each builds the same Material 3 scheme
// as on Android and applies it as the CSS custom properties style.css reads.

export const THEMES = [
  ["MATERIAL_YOU", "Material You"],
  ["LEMONADE", "Lemonade"],
  ["JET_BLACK", "Jet Black"],
  ["BLUSH", "Blush"],
  ["AMETHYST", "Amethyst"],
  ["FOREST", "Forest"],
  ["OCEAN", "Ocean"],
  ["EMBER", "Ember"],
].map(([id, label]) => ({ id, label }));

/* Colors are [r, g, b] in 0..1. */
const hex = (h) => [1, 3, 5].map((i) => parseInt(h.slice(i, i + 2), 16) / 255);
const WHITE = [1, 1, 1];
const BLACK = [0, 0, 0];
const toLinear = (c) =>
  c <= 0.04045 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
const toGamma = (c) =>
  c <= 0.0031308 ? c * 12.92 : 1.055 * c ** (1 / 2.4) - 0.055;
function oklab([r, g, b]) {
  [r, g, b] = [r, g, b].map(toLinear);
  const l = Math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b);
  const m = Math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b);
  const s = Math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b);
  return [
    0.2104542553 * l + 0.793617785 * m - 0.0040720468 * s,
    1.9779984951 * l - 2.428592205 * m + 0.4505937099 * s,
    0.0259040371 * l + 0.7827717662 * m - 0.808675766 * s,
  ];
}
function fromOklab([L, a, b]) {
  const l = (L + 0.3963377774 * a + 0.2158037573 * b) ** 3;
  const m = (L - 0.1055613458 * a - 0.0638541728 * b) ** 3;
  const s = (L - 0.0894841775 * a - 1.291485548 * b) ** 3;
  return [
    4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
    -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
    -0.0041960863 * l - 0.7034186147 * m + 1.707614701 * s,
  ].map((c) => Math.min(1, Math.max(0, toGamma(c))));
}
/** Compose's lerp, which blends in Oklab. */
function lerp(a, b, t) {
  const x = oklab(a),
    y = oklab(b);
  return fromOklab(x.map((v, i) => v + (y[i] - v) * t));
}
/** ColorUtils hue rotation through HSL. */
function shiftHue([r, g, b], degrees) {
  const max = Math.max(r, g, b),
    min = Math.min(r, g, b),
    l = (max + min) / 2,
    d = max - min;
  let h = 0,
    s = 0;
  if (d) {
    s = d / (1 - Math.abs(2 * l - 1));
    h =
      max === r
        ? ((g - b) / d) % 6
        : max === g
          ? (b - r) / d + 2
          : (r - g) / d + 4;
    h = (h * 60 + 360) % 360;
  }
  h = (h + degrees) % 360;
  const c = (1 - Math.abs(2 * l - 1)) * s,
    x = c * (1 - Math.abs(((h / 60) % 2) - 1)),
    m = l - c / 2;
  const [r1, g1, b1] =
    h < 60
      ? [c, x, 0]
      : h < 120
        ? [x, c, 0]
        : h < 180
          ? [0, c, x]
          : h < 240
            ? [0, x, c]
            : h < 300
              ? [x, 0, c]
              : [c, 0, x];
  return [r1 + m, g1 + m, b1 + m];
}

/** Builds a full scheme from one accent and one background, tinting the surfaces toward the accent. */
function palette(dark, primary, background) {
  const p = hex(primary),
    bg = hex(background);
  const ink = hex(dark ? "#E6E4EA" : "#1B1B1F");
  const tint = (t) => lerp(bg, p, t);
  const errors = dark
    ? ["#F2B8B5", "#8C1D18", "#F9DEDC"]
    : ["#B3261E", "#F9DEDC", "#410E0B"];
  return {
    primary: p,
    onPrimary: dark ? lerp(p, BLACK, 0.78) : WHITE,
    primaryContainer: dark ? tint(0.4) : lerp(WHITE, p, 0.2),
    onPrimaryContainer: dark ? lerp(p, WHITE, 0.8) : lerp(p, BLACK, 0.7),
    secondaryContainer: tint(dark ? 0.24 : 0.16),
    onSecondaryContainer: ink,
    error: hex(errors[0]),
    errorContainer: hex(errors[1]),
    onErrorContainer: hex(errors[2]),
    surface: bg,
    onSurface: ink,
    onSurfaceVariant: lerp(ink, bg, 0.25),
    outline: lerp(ink, bg, 0.5),
    outlineVariant: lerp(ink, bg, dark ? 0.75 : 0.78),
    inverseSurface: ink,
    inverseOnSurface: bg,
    containerLow: tint(0.03),
    container: tint(dark ? 0.06 : 0.055),
    containerHigh: tint(dark ? 0.09 : 0.08),
    containerHighest: tint(dark ? 0.12 : 0.11),
    tertiary: shiftHue(p, dark ? 55 : 70),
  };
}

/** Pure-black surfaces in every appearance mode, with neutral, high-contrast controls. */
const JET_BLACK = {
  primary: WHITE,
  onPrimary: BLACK,
  primaryContainer: BLACK,
  onPrimaryContainer: WHITE,
  secondaryContainer: BLACK,
  onSecondaryContainer: WHITE,
  error: hex("#FFB4AB"),
  errorContainer: BLACK,
  onErrorContainer: hex("#FFB4AB"),
  surface: BLACK,
  onSurface: WHITE,
  onSurfaceVariant: hex("#CCCCCC"),
  outline: hex("#808080"),
  outlineVariant: hex("#333333"),
  inverseSurface: BLACK,
  inverseOnSurface: WHITE,
  containerLow: BLACK,
  container: BLACK,
  containerHigh: BLACK,
  containerHighest: BLACK,
};

/** The GM3 baseline from ui/theme/Theme.kt; Material You falls back to it as on older Android. */
const BASELINE = {
  light: {
    primary: "#0B57D0",
    onPrimary: "#FFFFFF",
    primaryContainer: "#D3E3FD",
    onPrimaryContainer: "#041E49",
    secondaryContainer: "#C2E7FF",
    onSecondaryContainer: "#001D35",
    error: "#B3261E",
    errorContainer: "#F9DEDC",
    onErrorContainer: "#410E0B",
    surface: "#FFFFFF",
    onSurface: "#1F1F1F",
    onSurfaceVariant: "#444746",
    outline: "#747775",
    outlineVariant: "#C4C7C5",
    inverseSurface: "#303030",
    inverseOnSurface: "#F2F2F2",
    containerLow: "#F8FAFD",
    container: "#F0F4F9",
    containerHigh: "#E9EEF6",
    containerHighest: "#DDE3EA",
  },
  dark: {
    primary: "#A8C7FA",
    onPrimary: "#062E6F",
    primaryContainer: "#0842A0",
    onPrimaryContainer: "#D3E3FD",
    secondaryContainer: "#004A77",
    onSecondaryContainer: "#C2E7FF",
    error: "#F2B8B5",
    errorContainer: "#8C1D18",
    onErrorContainer: "#F9DEDC",
    surface: "#131314",
    onSurface: "#E3E3E3",
    onSurfaceVariant: "#C4C7C5",
    outline: "#8E918F",
    outlineVariant: "#444746",
    inverseSurface: "#E3E3E3",
    inverseOnSurface: "#303030",
    containerLow: "#1B1B1B",
    container: "#1E1F20",
    containerHigh: "#282A2C",
    containerHighest: "#333537",
  },
};
const fromHex = (scheme) =>
  Object.fromEntries(Object.entries(scheme).map(([k, v]) => [k, hex(v)]));

export const isDarkTheme = (theme, mode, systemDark) =>
  theme === "JET_BLACK" || (mode === "system" ? systemDark : mode === "dark");

export function schemeFor(theme, dark) {
  switch (theme) {
    case "JET_BLACK":
      return JET_BLACK;
    case "LEMONADE":
      return dark
        ? palette(true, "#EBDD68", "#171609")
        : {
            ...palette(false, "#716000", "#FFFCED"),
            primaryContainer: hex("#F8EA8A"),
            onPrimaryContainer: hex("#221F00"),
          };
    case "BLUSH":
      return dark
        ? palette(true, "#FF6FA8", "#190B13")
        : palette(false, "#C9266D", "#FFF6F9");
    case "AMETHYST":
      return dark
        ? palette(true, "#B78CFF", "#120B1D")
        : palette(false, "#8A3FE0", "#F9F5FF");
    case "FOREST":
      return dark
        ? palette(true, "#6FD39B", "#0F1511")
        : palette(false, "#2E7D52", "#F7F8F1");
    case "OCEAN":
      return dark
        ? palette(true, "#63B3F0", "#0C1519")
        : palette(false, "#1F6FB8", "#F5F9FC");
    case "EMBER":
      return dark
        ? palette(true, "#FFA06B", "#17100D")
        : palette(false, "#B5532A", "#FFF8F4");
    default:
      return fromHex(dark ? BASELINE.dark : BASELINE.light);
  }
}

export const css = (c) =>
  "#" +
  c
    .map((v) =>
      Math.round(v * 255)
        .toString(16)
        .padStart(2, "0"),
    )
    .join("");

const PROPS = {
  primary: "--primary",
  onPrimary: "--on-primary",
  primaryContainer: "--primary-container",
  onPrimaryContainer: "--on-primary-container",
  secondaryContainer: "--secondary-container",
  onSecondaryContainer: "--on-secondary-container",
  error: "--error",
  errorContainer: "--error-container",
  onErrorContainer: "--on-error-container",
  surface: "--surface",
  onSurface: "--on-surface",
  onSurfaceVariant: "--on-surface-variant",
  outline: "--outline",
  outlineVariant: "--outline-variant",
  inverseSurface: "--inverse-surface",
  inverseOnSurface: "--inverse-on-surface",
  containerLow: "--container-low",
  container: "--container",
  containerHigh: "--container-high",
  containerHighest: "--container-highest",
};

/** Sets the scheme's colors on the page. */
export function applyScheme(scheme, dark) {
  const root = document.documentElement;
  for (const [key, prop] of Object.entries(PROPS))
    if (scheme[key]) root.style.setProperty(prop, css(scheme[key]));
  root.style.colorScheme = dark ? "dark" : "light";
  root.dataset.theme = dark ? "dark" : "light";
  document
    .querySelector('meta[name="theme-color"]')
    ?.setAttribute("content", css(scheme.surface));
}
