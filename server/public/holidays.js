// The calendars a new account starts with, ported from data/CalendarRepository.kt
// (ensureDefaults, seedHolidays) and domain/CanadianHolidays.kt. Record IDs match the stable
// seed IDs the Android Cloud edition gives its own seed, so the two never duplicate each other.

const DAY = 86400000;
const YEARS = [];
for (let y = 2024; y <= 2050; y++) YEARS.push(y);
const ALL = "all provinces and territories";

const utc = (y, m, d) => Date.UTC(y, m, d);
const weekday = (ms) => new Date(ms).getUTCDay();
/** The [n]th [day] (0 = Sunday) of a month. */
const nth = (y, m, day, n) => {
  const first = utc(y, m, 1);
  return first + (((day - weekday(first) + 7) % 7) + (n - 1) * 7) * DAY;
};
function mondayNearest(y, m, d) {
  const date = utc(y, m, d);
  const before = date - ((weekday(date) + 6) % 7) * DAY;
  const after = date + ((8 - weekday(date)) % 7) * DAY;
  return date - before <= after - date ? before : after;
}
/** Western Easter Sunday (anonymous Gregorian algorithm). */
function easter(year) {
  const a = year % 19,
    b = Math.floor(year / 100),
    c = year % 100,
    d = Math.floor(b / 4),
    e = b % 4,
    f = Math.floor((b + 8) / 25),
    g = Math.floor((b - f + 1) / 3),
    h = (19 * a + b - d - g + 15) % 30,
    i = Math.floor(c / 4),
    k = c % 4,
    l = (32 + 2 * e + 2 * i - h - k) % 7,
    m = Math.floor((a + 11 * h + 22 * l) / 451);
  return utc(
    year,
    Math.floor((h + l - 7 * m + 114) / 31) - 1,
    ((h + l - 7 * m + 114) % 31) + 1,
  );
}

const fixed = (title, description, m, d) => ({
  title,
  description,
  fixed: true,
  rule: (y) => utc(y, m, d),
});
const dated = (title, description, rule) => ({
  title,
  description,
  fixed: false,
  rule,
});
const nthHoliday = (title, description, m, day, n) =>
  dated(title, description, (y) => nth(y, m, day, n));

/** Federal, provincial and territorial holidays in Canada, plus widely observed days. */
export const HOLIDAYS = [
  fixed("New Year's Day", `Statutory holiday in ${ALL}.`, 0, 1),
  fixed("Groundhog Day", "Observance.", 1, 2),
  fixed("Valentine's Day", "Observance.", 1, 14),
  nthHoliday(
    "Family Day",
    "Statutory holiday in AB, BC, ON, SK and NB. Also Louis Riel Day (MB), Islander Day (PE) and Heritage Day (NS).",
    1,
    1,
    3,
  ),
  fixed("St. Patrick's Day", "Observance; a provincial holiday in NL.", 2, 17),
  dated(
    "Good Friday",
    `Statutory holiday in ${ALL} except QC, where Good Friday or Easter Monday is observed.`,
    (y) => easter(y) - 2 * DAY,
  ),
  dated("Easter Sunday", "Observance.", easter),
  dated(
    "Easter Monday",
    "Optional holiday in several provinces and for federal employees; observed in QC.",
    (y) => easter(y) + DAY,
  ),
  dated(
    "St. George's Day",
    "Provincial holiday in NL, observed on the Monday nearest April 23.",
    (y) => mondayNearest(y, 3, 23),
  ),
  nthHoliday("Mother's Day", "Observance.", 4, 0, 2),
  dated(
    "Victoria Day",
    `Statutory holiday in ${ALL} except NB, NL, NS and NU. Called National Patriots' Day in QC.`,
    (y) => {
      const may25 = utc(y, 4, 25);
      return may25 - ((weekday(may25) + 6) % 7 || 7) * DAY;
    },
  ),
  nthHoliday("Father's Day", "Observance.", 5, 0, 3),
  fixed(
    "National Indigenous Peoples Day",
    "Statutory holiday in NT and YT; observance elsewhere.",
    5,
    21,
  ),
  fixed(
    "Saint-Jean-Baptiste Day",
    "Statutory holiday in QC (Fête nationale du Québec).",
    5,
    24,
  ),
  fixed("Canada Day", `Statutory holiday in ${ALL}. Memorial Day in NL.`, 6, 1),
  fixed("Nunavummiut Day", "Statutory holiday in NU.", 6, 9),
  dated(
    "Orangemen's Day",
    "Provincial holiday in NL, observed on the Monday nearest July 12.",
    (y) => mondayNearest(y, 6, 12),
  ),
  nthHoliday(
    "Civic Holiday",
    "Statutory holiday in several provinces and territories under local names: BC Day (BC), Heritage Day (AB), Saskatchewan Day (SK), Terry Fox Day (MB), Civic Holiday (ON, NT, NU), New Brunswick Day (NB), Natal Day (NS).",
    7,
    1,
    1,
  ),
  nthHoliday("Discovery Day", "Statutory holiday in YT.", 7, 1, 3),
  nthHoliday("Labour Day", `Statutory holiday in ${ALL}.`, 8, 1, 1),
  fixed(
    "National Day for Truth and Reconciliation",
    "Federal statutory holiday; also a statutory holiday in BC, MB, NT, NU, PE and YT.",
    8,
    30,
  ),
  nthHoliday(
    "Thanksgiving",
    `Statutory holiday in ${ALL} except NB, NL, NS and PE.`,
    9,
    1,
    2,
  ),
  fixed("Halloween", "Observance.", 9, 31),
  fixed(
    "Remembrance Day",
    "Statutory holiday in all provinces and territories except NS, ON and QC; observance elsewhere.",
    10,
    11,
  ),
  fixed("Christmas Eve", "Observance.", 11, 24),
  fixed("Christmas Day", `Statutory holiday in ${ALL}.`, 11, 25),
  fixed(
    "Boxing Day",
    "Statutory holiday federally and in ON; observed in most other provinces.",
    11,
    26,
  ),
  fixed("New Year's Eve", "Observance.", 11, 31),
];

const argb = (hex) => hex | 0;
const blankEvent = {
  description: "",
  location: "",
  allDay: true,
  color: null,
  recurrenceUntil: null,
  exceptions: [],
  reminders: [],
  kind: "EVENT",
  completions: [],
  importance: null,
};

/** The starter calendars and holiday events, as { kind, id, data } records. */
export function defaultRecords() {
  const records = [
    {
      kind: "calendar",
      id: "seed:calendar:Personal",
      data: {
        name: "Personal",
        color: argb(0xff039be5),
        visible: true,
        keywords: "",
        defaultImportance: null,
      },
    },
    {
      kind: "calendar",
      id: "seed:calendar:Work",
      data: {
        name: "Work",
        color: argb(0xff8e24aa),
        visible: true,
        keywords: "",
        defaultImportance: "HIGH",
      },
    },
    {
      kind: "calendar",
      id: "seed:calendar:Holidays",
      data: {
        name: "Holidays",
        color: argb(0xff479a8f),
        visible: true,
        keywords: "",
        defaultImportance: null,
      },
    },
  ];
  for (const holiday of HOLIDAYS)
    for (const start of holiday.fixed
      ? [holiday.rule(YEARS[0])]
      : YEARS.map(holiday.rule))
      records.push({
        kind: "event",
        id: "seed:holiday:" + sha256(`${holiday.title}:${start}`),
        data: {
          ...blankEvent,
          calendarId: "seed:calendar:Holidays",
          title: holiday.title,
          description: holiday.description,
          start,
          end: start + DAY,
          recurrence: holiday.fixed ? "YEARLY" : "NONE",
        },
      });
  return records;
}

/** SHA-256 of a UTF-8 string as hex. Kept local because crypto.subtle needs a secure origin. */
export function sha256(text) {
  const bytes = new TextEncoder().encode(text);
  const K = new Uint32Array([
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
    0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
    0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
    0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
  ]);
  const H = new Uint32Array([
    0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c,
    0x1f83d9ab, 0x5be0cd19,
  ]);
  const length = Math.ceil((bytes.length + 9) / 64) * 64;
  const data = new Uint8Array(length);
  data.set(bytes);
  data[bytes.length] = 0x80;
  const view = new DataView(data.buffer);
  view.setUint32(length - 4, bytes.length * 8);
  view.setUint32(length - 8, Math.floor((bytes.length * 8) / 2 ** 32));
  const w = new Uint32Array(64);
  const rotr = (x, n) => (x >>> n) | (x << (32 - n));
  for (let offset = 0; offset < length; offset += 64) {
    for (let i = 0; i < 16; i++) w[i] = view.getUint32(offset + i * 4);
    for (let i = 16; i < 64; i++) {
      const s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = w[i - 16] + s0 + w[i - 7] + s1;
    }
    let [a, b, c, d, e, f, g, h] = H;
    for (let i = 0; i < 64; i++) {
      const t1 =
        h +
        (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) +
        ((e & f) ^ (~e & g)) +
        K[i] +
        w[i];
      const t2 =
        (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) +
        ((a & b) ^ (a & c) ^ (b & c));
      h = g;
      g = f;
      f = e;
      e = (d + t1) >>> 0;
      d = c;
      c = b;
      b = a;
      a = (t1 + t2) >>> 0;
    }
    H[0] += a;
    H[1] += b;
    H[2] += c;
    H[3] += d;
    H[4] += e;
    H[5] += f;
    H[6] += g;
    H[7] += h;
  }
  return [...H].map((x) => x.toString(16).padStart(8, "0")).join("");
}
