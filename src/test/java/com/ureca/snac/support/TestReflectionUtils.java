package com.ureca.snac.support;

import java.lang.reflect.Field;

// 모든 Fixture에서 공통 사용하는 Reflection 유틸리티
public final class TestReflectionUtils {

    private TestReflectionUtils() {
    }

    public static void setField(Object target, String fieldName, Object value) {
        try {
            Field field = getField(target.getClass(), fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException("필드 설정 실패: " + fieldName, e);
        }
    }

    public static Field getField(Class<?> clazz, String fieldName) throws NoSuchFieldException {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            Class<?> superClass = clazz.getSuperclass();
            if (superClass != null) {
                return getField(superClass, fieldName);
            }
            throw e;
        }
    }
}
