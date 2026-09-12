package ru.arthaix.keystone.teunloadbatch;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;

/**
 * No-argument constructors for TileEntity.create. Vanilla calls Class.newInstance for every tile entity it loads from
 * disk, which checks the caller's access through Reflection.getCallerClass each time: about 10% of loading the city's
 * 4 million tile entities. Public constructors of public classes are looked up once and called without that check;
 * anything else still goes through Class.newInstance with its usual access rules.
 */
public final class TeConstructors {
    private static final ClassValue<Constructor<?>> CONSTRUCTORS = new ClassValue<Constructor<?>>() {
        @Override
        protected Constructor<?> computeValue(Class<?> type) {
            try {
                if (!Modifier.isPublic(type.getModifiers()) || Modifier.isAbstract(type.getModifiers())) {
                    return null;
                }
                Constructor<?> c = type.getDeclaredConstructor();
                if (!Modifier.isPublic(c.getModifiers())) {
                    return null;
                }
                c.setAccessible(true);
                return c;
            } catch (NoSuchMethodException | SecurityException e) {
                return null;
            }
        }
    };
    private static volatile boolean announced;

    private TeConstructors() {
    }

    public static Object newInstance(Class<?> type) throws InstantiationException, IllegalAccessException {
        Constructor<?> c = CONSTRUCTORS.get(type);
        if (c == null) {
            return type.newInstance();
        }
        if (!announced) {
            announced = true;
            System.out.println("[teunloadbatch] TileEntity.create uses cached constructors");
        }
        try {
            return c.newInstance();
        } catch (InvocationTargetException e) {
            // Class.newInstance rethrows whatever the constructor threw, unwrapped
            throw TeConstructors.<RuntimeException>sneaky(e.getCause());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T sneaky(Throwable t) throws T {
        throw (T) t;
    }
}
