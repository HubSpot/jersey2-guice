/*
 * Copyright 2014-2016 Squarespace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.squarespace.jersey2.guice;

import java.lang.annotation.Annotation;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.Descriptor;
import org.glassfish.hk2.api.DynamicConfiguration;
import org.glassfish.hk2.api.DynamicConfigurationService;
import org.glassfish.hk2.api.FactoryDescriptors;
import org.glassfish.hk2.api.Filter;
import org.glassfish.hk2.api.IndexedFilter;
import org.glassfish.hk2.api.Injectee;
import org.glassfish.hk2.api.MultiException;
import org.glassfish.hk2.api.Populator;
import org.glassfish.hk2.api.ServiceHandle;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.Binder;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.jvnet.hk2.internal.DynamicConfigurationImpl;
import org.jvnet.hk2.internal.ServiceLocatorImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A {@link ServiceLocatorImpl} that registers linked Guice bindings in HK2 by raw type,
 * just before the first lookup of that type, instead of all of them at link time.
 */
class GuiceServiceLocator extends ServiceLocatorImpl {

  private static final Logger LOG = LoggerFactory.getLogger(GuiceServiceLocator.class);

  private final ConcurrentMap<String, List<Binder>> pending = new ConcurrentHashMap<>();

  // HK2 looks services up while holding its write lock, so registering under any other
  // lock could deadlock against it.
  private final ReentrantReadWriteLock lock = hk2Lock(this);

  // Guarded by lock. Commits look up HK2 SPI types, which are never pending.
  private final Set<String> binding = new HashSet<>();

  private final ThreadLocal<Boolean> idempotentScan = new ThreadLocal<>();

  private volatile DynamicConfigurationService configurationService;

  private volatile long linkedServiceId;

  public GuiceServiceLocator(String name, ServiceLocator parent) {
    super(name, (ServiceLocatorImpl)parent);
    // HK2 resolves a child's lookups through its parent's private methods.
    if (parent instanceof GuiceServiceLocator) {
      ((GuiceServiceLocator)parent).bindAll();
    }
  }

  /**
   * Defers {@code binders}, keyed by their contract's raw class name.
   */
  void bindLazily(Map<String, List<Binder>> binders) {
    long max = 0L;
    for (ActiveDescriptor<?> descriptor : super.getDescriptors(BuilderHelper.allFilter())) {
      if (descriptor.getLocatorId() == getLocatorId()) {
        max = Math.max(max, descriptor.getServiceId());
      }
    }
    linkedServiceId = max;
    pending.putAll(binders);
  }

  /**
   * Returns {@code true} if it registered anything.
   */
  boolean bindFor(Type type) {
    if (pending.isEmpty()) {
      return false;
    }

    if (type instanceof Class<?>) {
      Class<?> clazz = (Class<?>)type;
      if (clazz.isPrimitive()) {
        clazz = MethodType.methodType(clazz).wrap().returnType();
      }
      return bindFor(clazz.getName());
    }

    // Provider<T>, Iterable<T>, IterableProvider<T> and Topic<T> lookups of T bypass the
    // public methods, so their type arguments are bound too.
    if (type instanceof ParameterizedType) {
      ParameterizedType parameterized = (ParameterizedType)type;
      boolean bound = bindFor(parameterized.getRawType());
      for (Type argument : parameterized.getActualTypeArguments()) {
        bound |= bindFor(argument);
      }
      return bound;
    }

    if (type instanceof GenericArrayType) {
      return bindFor(((GenericArrayType)type).getGenericComponentType());
    }

    if (type instanceof WildcardType) {
      boolean bound = false;
      for (Type upper : ((WildcardType)type).getUpperBounds()) {
        bound |= bindFor(upper);
      }
      return bound;
    }

    return false;
  }

  private boolean bindFor(String name) {
    if (!pending.containsKey(name)) {
      return false;
    }

    lock(name);
    try {
      List<Binder> binders = pending.get(name);
      if (binders == null || !binding.add(name)) {
        return false;
      }

      try {
        checkPrecedence(name);
        commit(binders);
        pending.remove(name);
        return true;
      } finally {
        binding.remove(name);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  void bindAll() {
    if (pending.isEmpty()) {
      return;
    }

    lock("every type");
    try {
      List<String> names = new ArrayList<>(pending.keySet());
      names.removeAll(binding);
      if (names.isEmpty()) {
        return;
      }

      LOG.info("Registering the remaining {} Guice binding types in HK2 for a lookup that isn't by type",
          names.size(), new Throwable("The lookup"));
      List<Binder> binders = new ArrayList<>();
      for (String name : names) {
        checkPrecedence(name);
        binders.addAll(pending.get(name));
      }

      binding.addAll(names);
      try {
        commit(binders);
        pending.keySet().removeAll(names);
      } finally {
        binding.removeAll(names);
      }
    } finally {
      lock.writeLock().unlock();
    }
  }

  /**
   * A read lock can't be upgraded, so waiting for the write lock under one would hang.
   */
  private void lock(String name) {
    if (lock.getReadHoldCount() > 0) {
      throw new IllegalStateException("Can't register the Guice bindings of " + name
          + " in HK2 while this thread holds HK2's read lock");
    }
    lock.writeLock().lock();
  }

  private static ReentrantReadWriteLock hk2Lock(ServiceLocatorImpl locator) {
    try {
      Field field = ServiceLocatorImpl.class.getDeclaredField("readWriteLock");
      field.setAccessible(true);
      return (ReentrantReadWriteLock)field.get(locator);
    } catch (ReflectiveOperationException err) {
      throw new IllegalStateException("Unsupported HK2 version", err);
    }
  }

  /**
   * Linking used to register every Guice binding before anything else, so a Guice binding
   * won HK2's tie-break (rank, then lowest service id) against a same-contract descriptor
   * added later. Registering it now would invert that.
   */
  private void checkPrecedence(String name) {
    for (ActiveDescriptor<?> descriptor : super.getDescriptors(BuilderHelper.createContractFilter(name))) {
      if (descriptor.getLocatorId() == getLocatorId() && descriptor.getServiceId() > linkedServiceId) {
        throw new IllegalStateException("Guice bindings of " + name
            + " are not yet in HK2, but " + descriptor + " was added after linking and would now take precedence over them");
      }
    }
  }

  private void commit(List<Binder> binders) {
    DynamicConfiguration dc = new DynamicConfigurationImpl(this);
    for (Binder binder : binders) {
      binder.bind(dc);
    }
    dc.commit();
  }

  List<ActiveDescriptor<?>> getDescriptorsWithoutBinding(Class<?> contract) {
    return super.getDescriptors(BuilderHelper.createContractFilter(contract.getName()));
  }

  private void bindFor(Filter filter) {
    if (isIndexed(filter)) {
      bindFor(((IndexedFilter)filter).getAdvertisedContract());
    } else {
      bindAll();
    }
  }

  @Override
  public void shutdown() {
    pending.clear();
    super.shutdown();
  }

  private static boolean isIndexed(Filter filter) {
    return filter instanceof IndexedFilter && ((IndexedFilter)filter).getAdvertisedContract() != null;
  }

  /**
   * Registers pending Guice bindings of a descriptor's contracts before HK2 gives the
   * descriptor its service id, so they keep precedence over it. A commit with idempotent
   * filters scans every descriptor for duplicates, but only the filters' contracts can
   * match, so only those are registered for that scan.
   */
  private final class Configuration extends DynamicConfigurationImpl {

    private final List<Filter> idempotentFilters = new ArrayList<>();

    Configuration() {
      super(GuiceServiceLocator.this);
    }

    @Override
    public <T> ActiveDescriptor<T> bind(Descriptor key, boolean requiresDeepCopy) {
      bindContracts(key);
      return super.bind(key, requiresDeepCopy);
    }

    @Override
    public FactoryDescriptors bind(FactoryDescriptors factoryDescriptors, boolean requiresDeepCopy) {
      if (factoryDescriptors != null) {
        bindContracts(factoryDescriptors.getFactoryAsAService());
        bindContracts(factoryDescriptors.getFactoryAsAFactory());
      }
      return super.bind(factoryDescriptors, requiresDeepCopy);
    }

    @Override
    public <T> ActiveDescriptor<T> addActiveDescriptor(ActiveDescriptor<T> activeDescriptor, boolean requiresDeepCopy) {
      bindContracts(activeDescriptor);
      return super.addActiveDescriptor(activeDescriptor, requiresDeepCopy);
    }

    private void bindContracts(Descriptor descriptor) {
      if (descriptor != null && descriptor.getAdvertisedContracts() != null) {
        for (String contract : descriptor.getAdvertisedContracts()) {
          bindFor(contract);
        }
      }
    }

    @Override
    public void addIdempotentFilter(Filter... filters) {
      super.addIdempotentFilter(filters);
      Collections.addAll(idempotentFilters, filters);
    }

    @Override
    public void commit() {
      if (idempotentFilters.isEmpty()) {
        super.commit();
        return;
      }

      for (Filter filter : idempotentFilters) {
        bindFor(filter);
      }

      idempotentScan.set(Boolean.TRUE);
      try {
        super.commit();
      } finally {
        idempotentScan.remove();
      }
    }
  }

  private final class ConfigurationService implements DynamicConfigurationService {

    private final DynamicConfigurationService delegate;

    ConfigurationService(DynamicConfigurationService delegate) {
      this.delegate = delegate;
    }

    @Override
    public DynamicConfiguration createDynamicConfiguration() {
      return new Configuration();
    }

    @Override
    public Populator getPopulator() {
      return delegate.getPopulator();
    }
  }

  @Override
  public List<ActiveDescriptor<?>> getDescriptors(Filter filter) {
    // HK2's duplicate scan is the first lookup that isn't by type in a commit.
    if (!isIndexed(filter) && idempotentScan.get() != null) {
      idempotentScan.remove();
      return super.getDescriptors(filter);
    }

    bindFor(filter);
    return super.getDescriptors(filter);
  }

  @Override
  public ActiveDescriptor<?> getBestDescriptor(Filter filter) {
    bindFor(filter);
    return super.getBestDescriptor(filter);
  }

  @Override
  public ActiveDescriptor<?> getInjecteeDescriptor(Injectee injectee) throws MultiException {
    bindFor(injectee.getRequiredType());
    return super.getInjecteeDescriptor(injectee);
  }

  @Override
  public <T> T getService(Class<T> contractOrImpl, Annotation... qualifiers) throws MultiException {
    return getService((Type)contractOrImpl, qualifiers);
  }

  @SuppressWarnings("unchecked")
  @Override
  public <T> T getService(Type contractOrImpl, Annotation... qualifiers) throws MultiException {
    bindFor(contractOrImpl);
    T service = super.getService(contractOrImpl, qualifiers);
    if (contractOrImpl == DynamicConfigurationService.class && qualifiers.length == 0 && service != null) {
      DynamicConfigurationService wrapper = configurationService;
      if (wrapper == null) {
        wrapper = new ConfigurationService((DynamicConfigurationService)service);
        configurationService = wrapper;
      }
      return (T)wrapper;
    }
    return service;
  }

  @Override
  public <T> T getService(Class<T> contractOrImpl, String name, Annotation... qualifiers) throws MultiException {
    bindFor(contractOrImpl);
    return super.getService(contractOrImpl, name, qualifiers);
  }

  @Override
  public <T> T getService(Type contractOrImpl, String name, Annotation... qualifiers) throws MultiException {
    bindFor(contractOrImpl);
    return super.getService(contractOrImpl, name, qualifiers);
  }

  @Override
  public <T> List<T> getAllServices(Class<T> contractOrImpl, Annotation... qualifiers) throws MultiException {
    bindFor(contractOrImpl);
    return super.getAllServices(contractOrImpl, qualifiers);
  }

  @Override
  public <T> List<T> getAllServices(Type contractOrImpl, Annotation... qualifiers) throws MultiException {
    bindFor(contractOrImpl);
    return super.getAllServices(contractOrImpl, qualifiers);
  }

  @Override
  public <T> List<T> getAllServices(Annotation qualifier, Annotation... qualifiers) throws MultiException {
    bindAll();
    return super.getAllServices(qualifier, qualifiers);
  }

  @Override
  public List<?> getAllServices(Filter searchCriteria) throws MultiException {
    bindFor(searchCriteria);
    return super.getAllServices(searchCriteria);
  }

  @Override
  public <T> ServiceHandle<T> getServiceHandle(Class<T> contractOrImpl, Annotation... qualifiers) throws MultiException {
    bindFor(contractOrImpl);
    return super.getServiceHandle(contractOrImpl, qualifiers);
  }

  @Override
  public <T> ServiceHandle<T> getServiceHandle(Type contractOrImpl, Annotation... qualifiers) throws MultiException {
    bindFor(contractOrImpl);
    return super.getServiceHandle(contractOrImpl, qualifiers);
  }

  @Override
  public <T> ServiceHandle<T> getServiceHandle(Class<T> contractOrImpl, String name, Annotation... qualifiers) throws MultiException {
    bindFor(contractOrImpl);
    return super.getServiceHandle(contractOrImpl, name, qualifiers);
  }

  @Override
  public <T> ServiceHandle<T> getServiceHandle(Type contractOrImpl, String name, Annotation... qualifiers) throws MultiException {
    bindFor(contractOrImpl);
    return super.getServiceHandle(contractOrImpl, name, qualifiers);
  }

  @Override
  public <T> List<ServiceHandle<T>> getAllServiceHandles(Class<T> contractOrImpl, Annotation... qualifiers) throws MultiException {
    bindFor(contractOrImpl);
    return super.getAllServiceHandles(contractOrImpl, qualifiers);
  }

  @Override
  public List<ServiceHandle<?>> getAllServiceHandles(Type contractOrImpl, Annotation... qualifiers) throws MultiException {
    bindFor(contractOrImpl);
    return super.getAllServiceHandles(contractOrImpl, qualifiers);
  }

  @Override
  public List<ServiceHandle<?>> getAllServiceHandles(Annotation qualifier, Annotation... qualifiers) throws MultiException {
    bindAll();
    return super.getAllServiceHandles(qualifier, qualifiers);
  }

  @Override
  public List<ServiceHandle<?>> getAllServiceHandles(Filter searchCriteria) throws MultiException {
    bindFor(searchCriteria);
    return super.getAllServiceHandles(searchCriteria);
  }
}
