package telekinesis.event;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;

public class Event {

    // beware: EventHandler instance MUST have a single Method, otherwise the
    // rest of the code breaks!
    public static interface EventHandler {

        public static interface H0 extends EventHandler {
            public void handle() throws Exception;
        }

        public static interface H1<A> extends EventHandler {
            public void handle(A a) throws Exception;
        }

        public static interface H2<A, B> extends EventHandler {
            public void handle(A a, B b) throws Exception;
        }

    }

    private static class Target<T extends EventHandler> {
        private final EventEmitter emitter;
        private final Class<T> handlerClass;

        private Target(EventEmitter emitter, Class<T> handlerClass) {
            this.emitter = emitter;
            this.handlerClass = handlerClass;
        }

        @Override
        public int hashCode() {
            return handlerClass.hashCode();
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Target other = (Target) obj;
            return emitter == other.emitter && handlerClass == other.handlerClass;
        }
    }

    private interface Invoker {
        void invoke(Object... params);
    }

    private static class Action {
        private final Invoker invoker;
        private final Object[] params;

        public Action(Invoker invoker, Object[] params) {
            this.invoker = invoker;
            this.params = params;
        }
    }

    private static final Map<Target<?>, Set<Invoker>> REGISTRY = new HashMap<Target<?>, Set<Invoker>>();
    private static final Map<Target<?>, Set<EventEmitter>> PASS_THROUGH = new HashMap<Target<?>, Set<EventEmitter>>();
    private static final BlockingDeque<Action> ACTION_QUEUE = new LinkedBlockingDeque<Action>();

    private static <T extends EventHandler> Class<T> getHandlerClass(T handler) {
        for (Class<?> iface : handler.getClass().getInterfaces()) {
            if (EventHandler.class.isAssignableFrom(iface)) {
                return (Class<T>) iface;
            }
        }
        throw new RuntimeException("supplied handler is not an instance of EventHandler");
    }

    private static <T extends EventHandler> Method getHandlerMethod(Class<T> handlerClass) {
        return handlerClass.getInterfaces()[0].getDeclaredMethods()[0];
    }

    public static <T extends EventHandler> void register(final EventEmitter emitter, final T handler) {
        final Class<T> handlerClass = getHandlerClass(handler);
        final Method m = getHandlerMethod(handlerClass);
        m.setAccessible(true);
        Target<T> t = new Target<T>(emitter, handlerClass);
        Set<Invoker> invokers = REGISTRY.get(t);
        if (invokers == null) {
            invokers = new HashSet<Invoker>();
            REGISTRY.put(t, invokers);
        }
        invokers.add(new Invoker() {
            @Override
            public void invoke(Object... params) {
                try {
                    m.invoke(handler, params);
                } catch (IllegalAccessException | IllegalArgumentException | InvocationTargetException e) {
                    throw new RuntimeException("handler call failed!", e);
                }
            }
        });
    }

    public static void deregisterEmitter(final EventEmitter emitter) {
        Iterator<Map.Entry<Target<?>, Set<Invoker>>> iter = REGISTRY.entrySet().iterator();
        while (iter.hasNext()) {
            Map.Entry<Target<?>, Set<Invoker>> entry = iter.next();
            if (entry.getKey().emitter == emitter) {
                iter.remove();
            }
        }
    }

    public static <T extends EventHandler> void passthrough(final EventEmitter emitter, final Class<T> handlerClass, final EventEmitter target) {
        Target<T> t = new Target<T>(emitter, handlerClass);
        Set<EventEmitter> emitters = PASS_THROUGH.get(t);
        if (emitters == null) {
            emitters = new HashSet<EventEmitter>();
            PASS_THROUGH.put(t, emitters);
        }
        emitters.add(target);
    }

    public static <T extends EventHandler> void emit(final EventEmitter emitter, final Class<T> handlerClass, final Object... params) {
        Target<T> t = new Target<T>(emitter, handlerClass);
        Set<EventEmitter> emitters = PASS_THROUGH.get(t);
        if (emitters != null) {
            for (EventEmitter target : emitters) {
                emit(target, handlerClass, params);
            }
        }
        Set<Invoker> invokers = REGISTRY.get(t);
        if (invokers == null) {
            return;
        }
        for (Invoker i : invokers) {
            ACTION_QUEUE.add(new Action(i, params));
        }
    }

    public static void executeNextAction() {
        try {
            Action a = ACTION_QUEUE.poll(500, TimeUnit.MILLISECONDS);
            if (a != null) {
                a.invoker.invoke(a.params);
            }
        } catch (InterruptedException e) {
        }
    }

    public static void executeRemainingActions() {
        Action a = null;
        while ((a = ACTION_QUEUE.poll()) != null) {
            a.invoker.invoke(a.params);
        }
    }

}