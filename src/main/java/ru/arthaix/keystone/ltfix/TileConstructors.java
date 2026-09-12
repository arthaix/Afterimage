package ru.arthaix.keystone.ltfix;

import java.lang.reflect.Constructor;

/** No-argument constructors of LittleTiles tile classes, looked up once per class instead of once per created tile. */
public final class TileConstructors {
    private static final ClassValue<Constructor<?>> CONSTRUCTORS = new ClassValue<Constructor<?>>() {
        @Override
        protected Constructor<?> computeValue(Class<?> type) {
            try {
                return type.getConstructor();
            } catch (NoSuchMethodException e) {
                return null;
            }
        }
    };

    private TileConstructors() {
    }

    /** Same result and failure as LittleTileType.createTile: clazz.getConstructor().newInstance(). */
    public static Object create(Class<?> type, String id) {
        try {
            Constructor<?> c = CONSTRUCTORS.get(type);
            if (c == null) {
                throw new NoSuchMethodException(type.getName() + ".<init>()");
            }
            return c.newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Invalid type " + id, e);
        }
    }
}
