package runen;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.ArrayReference;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.LocatableEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Java-Debugger ueber JDI: verbindet sich per JDWP (Socket) mit einer laufenden JVM. */
final class JavaDebug implements Debug.Backend {
    private final Debug.Ui ui;
    private VirtualMachine vm;
    private EventRequestManager erm;
    private ThreadReference halt;
    private final Map<Path, List<Integer>> punkte = new HashMap<>();
    private final Map<Path, List<BreakpointRequest>> anfragen = new HashMap<>();
    private final Map<String, ClassPrepareRequest> vorbereitet = new HashMap<>();
    private volatile boolean getrennt;
    /** JDI blockiert: alles in einem eigenen Faden */
    private final ExecutorService faden = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "jdi");
        t.setDaemon(true);
        return t;
    });
    private static final Set<String> BOXEN = Set.of("java.lang.Integer", "java.lang.Long", "java.lang.Short", "java.lang.Byte", "java.lang.Double",
            "java.lang.Float", "java.lang.Boolean", "java.lang.Character");

    JavaDebug(Debug.Ui ui) {
        this.ui = ui;
    }

    @Override
    public String name() {
        return "Java";
    }

    /** an host:port anhaengen; versucht es bis zu sekunden lang (die JVM startet evtl. noch) */
    void verbinden(String host, int port, int sekunden) {
        faden.submit(() -> {
            AttachingConnector c = null;
            for (Connector k : Bootstrap.virtualMachineManager().attachingConnectors()) {
                if (k.name().equals("com.sun.jdi.SocketAttach")) c = (AttachingConnector) k;
            }
            if (c == null) {
                ui.fehler("kein SocketAttach-Connector (laeuft der Editor mit einem JDK?)");
                ui.beendet(null);
                return;
            }
            Map<String, Connector.Argument> a = c.defaultArguments();
            a.get("hostname").setValue(host);
            a.get("port").setValue(String.valueOf(port));
            long bis = System.currentTimeMillis() + sekunden * 1000L;
            Exception letzter = null;
            ui.meldung("verbinde mit " + host + ":" + port + " ...");
            while (System.currentTimeMillis() < bis) {
                try {
                    vm = c.attach(a);
                    break;
                } catch (Exception e) {
                    letzter = e;
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
            if (vm == null) {
                ui.fehler("keine Verbindung zu " + host + ":" + port + (letzter == null ? "" : ": " + letzter.getMessage()));
                ui.beendet(null);
                return;
            }
            erm = vm.eventRequestManager();
            for (var e : ui.haltepunkte().entrySet()) setzen(e.getKey(), e.getValue());
            ui.meldung("verbunden mit " + vm.name() + " " + vm.version() + " (" + host + ":" + port + ")");
            ui.laeuft();
            Thread t = new Thread(this::ereignisse, "jdi-ereignisse");
            t.setDaemon(true);
            t.start();
        });
    }

    private void ereignisse() {
        try {
            while (true) {
                EventSet es = vm.eventQueue().remove();
                boolean anhalten = false;
                for (Event e : es) {
                    if (e instanceof ClassPrepareEvent cp) {
                        faden.submit(() -> klasseGeladen(cp.referenceType()));
                    } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                        ui.beendet(getrennt ? "getrennt, die Anwendung laeuft weiter" : "die JVM ist beendet");
                        return;
                    } else if (e instanceof LocatableEvent le) {
                        anhalten = true;
                        ThreadReference t = le.thread();
                        String grund = e instanceof BreakpointEvent ? "Haltepunkt" : "Schritt";
                        faden.submit(() -> angehalten(t, grund));
                    }
                }
                // Klassen-Ereignisse halten nicht an: erst Haltepunkte setzen, dann weiter
                if (!anhalten) faden.submit(es::resume);
            }
        } catch (InterruptedException | com.sun.jdi.VMDisconnectedException e) {
            ui.beendet("Verbindung getrennt");
        }
    }

    private void angehalten(ThreadReference t, String grund) {
        halt = t;
        for (StepRequest r : new ArrayList<>(erm.stepRequests())) erm.deleteEventRequest(r);
        List<Debug.Frame> l = new ArrayList<>();
        try {
            int i = 0;
            for (StackFrame f : t.frames()) {
                Location loc = f.location();
                String typ = loc.declaringType().name();
                String kurz = typ.substring(typ.lastIndexOf('.') + 1);
                Path datei = null;
                try {
                    datei = ui.quelle(loc.sourcePath());
                } catch (AbsentInformationException e) {
                    // ohne Quelle
                }
                l.add(new Debug.Frame(kurz + "." + loc.method().name() + "()", datei, Math.max(0, loc.lineNumber() - 1), i++));
                if (i >= 200) break;
            }
        } catch (IncompatibleThreadStateException e) {
            ui.fehler(e.toString());
        }
        ui.angehalten(l, grund + " in Faden " + t.name());
    }

    // ---------------------------------------------------------------- Haltepunkte
    @Override
    public void haltepunkte(Path datei, List<Integer> zeilen) {
        faden.submit(() -> setzen(datei, zeilen));
    }

    private void setzen(Path datei, List<Integer> zeilen) {
        punkte.put(datei, zeilen);
        if (vm == null) return;
        for (BreakpointRequest r : anfragen.getOrDefault(datei, List.of())) erm.deleteEventRequest(r);
        anfragen.put(datei, new ArrayList<>());
        String name = datei.getFileName().toString();
        if (!vorbereitet.containsKey(name)) {
            ClassPrepareRequest cp = erm.createClassPrepareRequest();
            cp.addSourceNameFilter(name);
            cp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            cp.enable();
            vorbereitet.put(name, cp);
        }
        for (ReferenceType rt : vm.allClasses()) klasseGeladen(rt, datei);
    }

    private void klasseGeladen(ReferenceType rt) {
        for (Path p : new ArrayList<>(punkte.keySet())) klasseGeladen(rt, p);
    }

    /** Haltepunkte der Datei in dieser Klasse setzen, wenn die Klasse aus der Datei stammt */
    private void klasseGeladen(ReferenceType rt, Path datei) {
        try {
            if (!rt.sourceName().equals(datei.getFileName().toString())) return;
            String typ = rt.name();
            int punkt = typ.lastIndexOf('.');
            String paket = punkt < 0 ? "" : typ.substring(0, punkt).replace('.', '/');
            String ordner = datei.getParent() == null ? "" : datei.getParent().toString().replace('\\', '/');
            if (!paket.isEmpty() && !ordner.endsWith("/" + paket) && !ordner.equals(paket)) return;
        } catch (AbsentInformationException e) {
            return;
        }
        for (int z : punkte.getOrDefault(datei, List.of())) {
            try {
                for (Location loc : rt.locationsOfLine(z + 1)) {
                    BreakpointRequest r = erm.createBreakpointRequest(loc);
                    r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
                    r.enable();
                    anfragen.computeIfAbsent(datei, k -> new ArrayList<>()).add(r);
                }
            } catch (AbsentInformationException e) {
                // ohne Zeilennummern
            }
        }
    }

    // ---------------------------------------------------------------- Steuern
    @Override
    public void weiter() {
        faden.submit(() -> {
            if (vm == null || halt == null) return;
            halt = null;
            ui.laeuft();
            vm.resume();
        });
    }

    @Override
    public void schritt(int art) {
        faden.submit(() -> {
            if (vm == null || halt == null) {
                ui.meldung("nicht angehalten");
                return;
            }
            for (StepRequest r : new ArrayList<>(erm.stepRequests())) erm.deleteEventRequest(r);
            int tiefe = art == 1 ? StepRequest.STEP_INTO : art == 2 ? StepRequest.STEP_OUT : StepRequest.STEP_OVER;
            StepRequest r = erm.createStepRequest(halt, StepRequest.STEP_LINE, tiefe);
            for (String f : new String[] {"java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*"}) r.addClassExclusionFilter(f);
            r.addCountFilter(1);
            r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            r.enable();
            halt = null;
            ui.laeuft();
            vm.resume();
        });
    }

    @Override
    public void stop() {
        faden.submit(() -> {
            if (vm == null) return;
            getrennt = true;
            try {
                // nur trennen: die Anwendung laeuft weiter
                vm.dispose();
            } catch (com.sun.jdi.VMDisconnectedException e) {
                // schon weg
            }
            vm = null;
        });
    }

    // ---------------------------------------------------------------- Variablen
    @Override
    public void variablen(Debug.Frame f, Consumer<List<Debug.Var>> fertig) {
        ThreadReference t = halt;
        faden.submit(() -> {
            List<Debug.Var> l = new ArrayList<>();
            try {
                StackFrame sf = t.frame((int) f.ref());
                ObjectReference dies = sf.thisObject();
                if (dies != null) l.add(var("this", dies));
                try {
                    List<LocalVariable> lv = sf.visibleVariables();
                    Map<LocalVariable, Value> w = sf.getValues(lv);
                    for (LocalVariable v : lv) l.add(var(v.name(), w.get(v)));
                } catch (AbsentInformationException e) {
                    l.add(new Debug.Var("(keine Debug-Infos: mit -g uebersetzen)", "", null, false));
                }
            } catch (Exception e) {
                l.add(new Debug.Var("(nicht lesbar)", e.toString(), null, false));
            }
            fertig.accept(l);
        });
    }

    @Override
    public void kinder(Debug.Var v, Consumer<List<Debug.Var>> fertig) {
        faden.submit(() -> {
            List<Debug.Var> l = new ArrayList<>();
            try {
                if (v.ref instanceof ArrayReference a) {
                    int n = Math.min(a.length(), 200);
                    List<Value> w = a.getValues(0, n);
                    for (int i = 0; i < n; i++) l.add(var("[" + i + "]", w.get(i)));
                    if (a.length() > n) l.add(new Debug.Var("...", (a.length() - n) + " weitere", null, false));
                } else if (v.ref instanceof ObjectReference o) {
                    List<Field> felder = new ArrayList<>();
                    for (Field fe : o.referenceType().allFields()) if (!fe.isStatic()) felder.add(fe);
                    Map<Field, Value> w = o.getValues(felder);
                    for (Field fe : felder) l.add(var(fe.name(), w.get(fe)));
                }
            } catch (Exception e) {
                l.add(new Debug.Var("(nicht lesbar)", e.toString(), null, false));
            }
            fertig.accept(l);
        });
    }

    private static Debug.Var var(String name, Value v) {
        if (v == null) return new Debug.Var(name, "null", null, false);
        if (v instanceof PrimitiveValue) return new Debug.Var(name, v.toString(), null, false);
        if (v instanceof StringReference s) {
            String t = s.value();
            return new Debug.Var(name, "\"" + (t.length() > 300 ? t.substring(0, 300) + "..." : t) + "\"", null, false);
        }
        if (v instanceof ArrayReference a) {
            String typ = a.referenceType().name();
            return new Debug.Var(name, typ.replace("[]", "[" + a.length() + "]"), a, a.length() > 0);
        }
        ObjectReference o = (ObjectReference) v;
        String typ = o.referenceType().name();
        String kurz = typ.substring(typ.lastIndexOf('.') + 1);
        if (BOXEN.contains(typ)) {
            Field f = o.referenceType().fieldByName("value");
            if (f != null) return new Debug.Var(name, String.valueOf(o.getValue(f)), o, false);
        }
        if (o.referenceType() instanceof ClassType ct && ct.isEnum()) {
            Field f = ct.fieldByName("name");
            Value n = f == null ? null : o.getValue(f);
            if (n instanceof StringReference s) return new Debug.Var(name, kurz + "." + s.value(), o, true);
        }
        return new Debug.Var(name, kurz + " (id=" + o.uniqueID() + ")", o, true);
    }
}
