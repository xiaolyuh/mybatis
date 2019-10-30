/**
 * Copyright 2009-2019 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.ibatis.binding;

import org.apache.ibatis.reflection.ExceptionUtil;
import org.apache.ibatis.session.SqlSession;

import java.io.Serializable;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.util.Map;

/**
 * Mapper接口的增强器
 * 代理模式
 *
 * @author Clinton Begin
 * @author Eduardo Macarron
 */
public class MapperProxy<T> implements InvocationHandler, Serializable {

  private static final long serialVersionUID = -6424540398559729838L;
  private static final int ALLOWED_MODES = MethodHandles.Lookup.PRIVATE | MethodHandles.Lookup.PROTECTED
    | MethodHandles.Lookup.PACKAGE | MethodHandles.Lookup.PUBLIC;
  private static final Constructor<Lookup> lookupConstructor;

  /**
   * 根据MethodHandles.Class对象中是否有privateLookupIn方法来判断是JDK8 还是9
   */
  private static final Method privateLookupInMethod;

  /**
   * 记录关联的sqlsession对象
   */
  private final SqlSession sqlSession;

  /**
   * Mapper接口对应的Class对象
   */
  private final Class<T> mapperInterface;

  /**
   * MapperMethod缓存
   * MapperMethod对象不记录任何状态信息，所以它可以在多个代理对象之间共享
   */
  private final Map<Method, MapperMethod> methodCache;

  public MapperProxy(SqlSession sqlSession, Class<T> mapperInterface, Map<Method, MapperMethod> methodCache) {
    this.sqlSession = sqlSession;
    this.mapperInterface = mapperInterface;
    this.methodCache = methodCache;
  }

  static {
    Method privateLookupIn;
    try {
      privateLookupIn = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
    } catch (NoSuchMethodException e) {
      privateLookupIn = null;
    }
    privateLookupInMethod = privateLookupIn;

    Constructor<Lookup> lookup = null;
    if (privateLookupInMethod == null) {
      // JDK 1.8
      try {
        lookup = MethodHandles.Lookup.class.getDeclaredConstructor(Class.class, int.class);
        lookup.setAccessible(true);
      } catch (NoSuchMethodException e) {
        throw new IllegalStateException(
          "There is neither 'privateLookupIn(Class, Lookup)' nor 'Lookup(Class, int)' method in java.lang.invoke.MethodHandles.",
          e);
      } catch (Throwable t) {
        lookup = null;
      }
    }
    lookupConstructor = lookup;
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    try {
      // 如果是Object本身的方法不增强
      if (Object.class.equals(method.getDeclaringClass())) {
        return method.invoke(this, args);
      }
      // 判断是否是默认方法
      else if (method.isDefault()) {
        if (privateLookupInMethod == null) {
          return invokeDefaultMethodJava8(proxy, method, args);
        } else {
          return invokeDefaultMethodJava9(proxy, method, args);
        }
      }
    } catch (Throwable t) {
      throw ExceptionUtil.unwrapThrowable(t);
    }
    // 从缓存中获取MapperMethod对象
    final MapperMethod mapperMethod = cachedMapperMethod(method);
    // 执行MapperMethod
    return mapperMethod.execute(sqlSession, args);
  }

  private MapperMethod cachedMapperMethod(Method method) {
    return methodCache.computeIfAbsent(method,
      k -> new MapperMethod(mapperInterface, method, sqlSession.getConfiguration()));
  }

  private Object invokeDefaultMethodJava9(Object proxy, Method method, Object[] args)
    throws Throwable {
    final Class<?> declaringClass = method.getDeclaringClass();
    return ((Lookup) privateLookupInMethod.invoke(null, declaringClass, MethodHandles.lookup()))
      .findSpecial(declaringClass, method.getName(),
        MethodType.methodType(method.getReturnType(), method.getParameterTypes()), declaringClass)
      .bindTo(proxy).invokeWithArguments(args);
  }

  private Object invokeDefaultMethodJava8(Object proxy, Method method, Object[] args)
    throws Throwable {
    final Class<?> declaringClass = method.getDeclaringClass();
    return lookupConstructor.newInstance(declaringClass, ALLOWED_MODES).unreflectSpecial(method, declaringClass)
      .bindTo(proxy).invokeWithArguments(args);
  }
}
