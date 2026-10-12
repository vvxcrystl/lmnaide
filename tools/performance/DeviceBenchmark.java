import android.os.Debug;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Runs the APK's actual domain classes on ART, without reading or changing app/user data. */
public final class DeviceBenchmark {
    private static volatile Object sink;
    private interface Work { Object run() throws Exception; }

    public static void main(String[] args) throws Exception {
        Class<?> eventType = Class.forName("dev.lmnaide.calendar.data.EventEntity");
        Object daily = Class.forName("dev.lmnaide.calendar.data.Recurrence").getField("DAILY").get(null);
        Object kind = Class.forName("dev.lmnaide.calendar.data.EventKind").getField("EVENT").get(null);
        LocalDate today = LocalDate.of(2026, 10, 11);
        long start = today.minusYears(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli();
        List<Object> events = new ArrayList<>();
        Constructor<?> eventConstructor = constructor(eventType, 17);
        for (int i = 0; i < 250; i++) {
            events.add(eventConstructor.newInstance((long)i, 1L, "Daily " + i, "", "",
                start, start + 86400000L, true, null, daily, null, Collections.emptyList(),
                Collections.emptyList(), kind, Collections.emptyList(), null, Collections.emptyList()));
        }
        Object calendar = constructor(Class.forName("dev.lmnaide.calendar.data.CalendarEntity"), 6)
            .newInstance(1L, "Personal", 42, true, "", null);
        Class<?> indexType = Class.forName("dev.lmnaide.calendar.domain.EventIndex");
        Object index = constructor(indexType, 3).newInstance(events, Collections.singletonList(calendar), true);
        Method representative = indexType.getMethod("representative", eventType, LocalDate.class);
        Work search = () -> {
            List<Object> results = new ArrayList<>();
            for (Object event : events) results.add(representative.invoke(index, event, today));
            return results;
        };
        List<?> results = (List<?>)search.run();
        if (results.size() != 250 || results.contains(null)) throw new AssertionError("Missing results");
        for (Object occurrence : results) {
            if (!today.equals(occurrence.getClass().getMethod("getStartDate").invoke(occurrence))) {
                throw new AssertionError("Unexpected occurrence date");
            }
        }
        if (args.length == 0 || !args[0].equals("contacts")) measure("250 daily series / next occurrence", search);
        if (args.length > 0 && args[0].equals("search")) return;

        Constructor<?> person = constructor(Class.forName("dev.lmnaide.calendar.contacts.EventPerson"), 2);
        List<Object> contacts = new ArrayList<>();
        for (int i = 0; i < 500; i++) contacts.add(person.newInstance("Person " + i + " Family" + (i % 100), "" + i));
        Object matcher;
        Method match;
        boolean indexed;
        try {
            Class<?> type = Class.forName("dev.lmnaide.calendar.contacts.ContactMatcher$Index");
            matcher = constructor(type, 1).newInstance(contacts);
            match = type.getMethod("matches", String.class);
            indexed = true;
        } catch (ClassNotFoundException absentOnBaseline) {
            Class<?> type = Class.forName("dev.lmnaide.calendar.contacts.ContactMatcher");
            matcher = type.getField("INSTANCE").get(null);
            match = type.getMethod("matches", String.class, List.class);
            indexed = false;
        }
        final Object target = matcher;
        final Method method = match;
        final boolean reuseIndex = indexed;
        Work matching = () -> {
            List<Object> matches = new ArrayList<>();
            for (String name : new String[]{"Person 100", "Family50", "Missing"}) {
                matches.add(reuseIndex ? method.invoke(target, name) : method.invoke(target, name, contacts));
            }
            return matches;
        };
        List<?> matches = (List<?>)matching.run();
        if (((List<?>)matches.get(0)).size() != 1 || ((List<?>)matches.get(1)).size() != 5 ||
                !((List<?>)matches.get(2)).isEmpty()) throw new AssertionError("Wrong contact matches");
        measure("3 names / 500 contacts / index setup excluded", matching);
    }

    private static Constructor<?> constructor(Class<?> type, int parameters) {
        for (Constructor<?> candidate : type.getConstructors()) {
            if (candidate.getParameterTypes().length == parameters) return candidate;
        }
        throw new IllegalArgumentException("Constructor not found: " + type);
    }

    private static void measure(String name, Work work) throws Exception {
        for (int i = 0; i < 5; i++) sink = work.run();
        long[] nanos = new long[11];
        long[] bytes = new long[11];
        for (int i = 0; i < nanos.length; i++) {
            long allocated = Long.parseLong(Debug.getRuntimeStat("art.gc.bytes-allocated"));
            long start = System.nanoTime();
            sink = work.run();
            nanos[i] = System.nanoTime() - start;
            bytes[i] = Long.parseLong(Debug.getRuntimeStat("art.gc.bytes-allocated")) - allocated;
        }
        Arrays.sort(nanos);
        Arrays.sort(bytes);
        System.out.println(name + ": median_ms=" + (nanos[5] / 1000000.0) + ", median_allocated_bytes=" + bytes[5]);
    }
}
