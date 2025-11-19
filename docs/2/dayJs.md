## Day.js Documentation

## Day.js Documentation

Day.js is a minimalist JavaScript library for date and time manipulation, providing a modern API similar to Moment.js but much smaller in size.

### Installation

Using pnpm:

```bash
pnpm add dayjs
pnpm add @types/dayjs  # optional, for TypeScript
```

### Plugins

Day.js supports plugins to extend its functionality. Commonly used plugins include:

* **UTC**: `dayjs/plugin/utc` — handles UTC date and time.
* **Timezone**: `dayjs/plugin/timezone` — supports time zone conversions.

### Usage

```ts
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'

// Extend dayjs with plugins
dayjs.extend(utc)
dayjs.extend(timezone)

// Example: get current time in a specific timezone
const nowInNairobi = dayjs().tz('Africa/Nairobi').format()
console.log(nowInNairobi)

// Example: convert UTC to local time
const utcTime = dayjs.utc('2025-11-19T12:00:00Z')
const localTime = utcTime.local().format()
console.log(localTime)
```

### Features

* Immutable and chainable API.
* Lightweight (< 2KB).
* Supports plugins for UTC, timezone, relative time, advanced formatting, etc.
* Simple parsing and formatting.

### Notes

* Do **not** try to install timezone or utc as separate npm packages. They are available via Day.js's plugin system.
* For time zone operations, always load `utc` plugin first, then `timezone`.


Day.js is a minimalist JavaScript library for date and time manipulation, providing a modern API similar to Moment.js but much smaller in size.

### Installation

Using pnpm:

```bash
pnpm add dayjs
pnpm add @types/dayjs  # optional, for TypeScript
```

### Plugins

Day.js supports plugins to extend its functionality. Commonly used plugins include:

* **UTC**: `dayjs/plugin/utc` — handles UTC date and time.
* **Timezone**: `dayjs/plugin/timezone` — supports time zone conversions.

### Usage

```ts
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'

// Extend dayjs with plugins
dayjs.extend(utc)
dayjs.extend(timezone)

// Example: get current time in a specific timezone
const nowInNairobi = dayjs().tz('Africa/Nairobi').format()
console.log(nowInNairobi)

// Example: convert UTC to local time
const utcTime = dayjs.utc('2025-11-19T12:00:00Z')
const localTime = utcTime.local().format()
console.log(localTime)
```

### Features

* Immutable and chainable API.
* Lightweight (< 2KB).
* Supports plugins for UTC, timezone, relative time, advanced formatting, etc.
* Simple parsing and formatting.

### Notes

* Do **not** try to install timezone or utc as separate npm packages. They are available via Day.js's plugin system.
* For time zone operations, always load `utc` plugin first, then `timezone`.
## Day.js Documentation

Day.js is a minimalist JavaScript library for date and time manipulation, providing a modern API similar to Moment.js but much smaller in size.

### Installation

Using pnpm:

```bash
pnpm add dayjs
pnpm add @types/dayjs  # optional, for TypeScript
```

### Plugins

Day.js supports plugins to extend its functionality. Commonly used plugins include:

* **UTC**: `dayjs/plugin/utc` — handles UTC date and time.
* **Timezone**: `dayjs/plugin/timezone` — supports time zone conversions.

### Usage

```ts
import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import timezone from 'dayjs/plugin/timezone'

// Extend dayjs with plugins
dayjs.extend(utc)
dayjs.extend(timezone)

// Example: get current time in a specific timezone
const nowInNairobi = dayjs().tz('Africa/Nairobi').format()
console.log(nowInNairobi)

// Example: convert UTC to local time
const utcTime = dayjs.utc('2025-11-19T12:00:00Z')
const localTime = utcTime.local().format()
console.log(localTime)
```

### Features

* Immutable and chainable API.
* Lightweight (< 2KB).
* Supports plugins for UTC, timezone, relative time, advanced formatting, etc.
* Simple parsing and formatting.

### Notes

* Do **not** try to install timezone or utc as separate npm packages. They are available via Day.js's plugin system.
* For time zone operations, always load `utc` plugin first, then `timezone`.
