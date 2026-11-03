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

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.api.DynamicConfiguration;
import org.glassfish.hk2.api.DynamicConfigurationService;
import org.glassfish.hk2.api.IterableProvider;
import org.glassfish.hk2.api.MultiException;
import org.glassfish.hk2.api.ServiceLocator;
import org.glassfish.hk2.utilities.BuilderHelper;
import org.glassfish.hk2.utilities.ServiceLocatorUtilities;
import org.jvnet.hk2.internal.DynamicConfigurationImpl;
import org.jvnet.hk2.internal.ServiceLocatorImpl;
import org.testng.annotations.Test;

import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.multibindings.Multibinder;
import com.google.inject.name.Names;

public class LazyBindingTest {

  interface Greeter {
    String greet();
  }

  static class Named implements Greeter {
    @Override
    public String greet() {
      return "named";
    }
  }

  interface Plugin {
    String name();
  }

  static Plugin plugin(String name) {
    return () -> name;
  }

  static class Unrelated {}

  static class NeedsPlugins {
    final IterableProvider<Plugin> plugins;

    @Inject
    NeedsPlugins(IterableProvider<Plugin> plugins) {
      this.plugins = plugins;
    }
  }

  private static final Greeter GUICE_GREETER = () -> "guice";

  private static ServiceLocator link() {
    ServiceLocator locator = JerseyGuiceUtils.newServiceLocator();
    JerseyGuiceUtils.link(locator, Guice.createInjector(new AbstractModule() {
      @Override
      protected void configure() {
        bind(Greeter.class).annotatedWith(Names.named("only")).to(Named.class);
        Multibinder<Plugin> plugins = Multibinder.newSetBinder(binder(), Plugin.class);
        plugins.addBinding().toInstance(plugin("a"));
        plugins.addBinding().toInstance(plugin("b"));
        bind(Unrelated.class).toInstance(new Unrelated());
      }
    }));
    return locator;
  }

  private static boolean registered(ServiceLocator locator, Class<?> type) {
    return !((GuiceServiceLocator)locator).getDescriptorsWithoutBinding(type).isEmpty();
  }

  private static Set<String> names(List<Plugin> plugins) {
    return plugins.stream().map(Plugin::name).collect(Collectors.toSet());
  }

  @Test
  public void bindingsAreRegisteredOnFirstLookupOfTheirType() {
    ServiceLocator locator = link();
    assertTrue(!registered(locator, Plugin.class));
    assertTrue(!registered(locator, Unrelated.class));

    assertEquals(names(locator.getAllServices(Plugin.class)), Set.of("a", "b"));

    assertTrue(registered(locator, Plugin.class));
    assertTrue(!registered(locator, Unrelated.class));
  }

  @Test
  public void unqualifiedLookupFindsAQualifiedOnlyBinding() {
    assertEquals(link().getService(Greeter.class).greet(), "named");
  }

  @Test
  public void injectedIterableProviderSeesEveryBinding() {
    ServiceLocator locator = link();
    NeedsPlugins needs = locator.createAndInitialize(NeedsPlugins.class);
    Set<String> names = new HashSet<>();
    needs.plugins.forEach(p -> names.add(p.name()));
    assertEquals(names, Set.of("a", "b"));
  }

  @Test
  public void lookupThatIsNotByTypeRegistersEverything() {
    ServiceLocator locator = link();
    List<ActiveDescriptor<?>> all = locator.getDescriptors(BuilderHelper.allFilter());
    assertTrue(all.stream().anyMatch(d -> d.getAdvertisedContracts().contains(Unrelated.class.getName())));
    assertTrue(registered(locator, Plugin.class));
  }

  @Test
  public void childLocatorSeesEveryBinding() {
    ServiceLocator child = JerseyGuiceUtils.newServiceLocator(null, link());
    assertEquals(names(child.getAllServices(Plugin.class)), Set.of("a", "b"));
  }

  @Test
  public void guiceBindingKeepsPrecedenceOverLaterDescriptors() {
    ServiceLocator locator = link();
    Greeter guice = locator.getService(Greeter.class);
    ServiceLocatorUtilities.addOneConstant(locator, GUICE_GREETER, null, Greeter.class);
    assertSame(locator.getService(Greeter.class).getClass(), guice.getClass());
  }

  @Test
  public void laterDescriptorOfAPendingTypeDoesNotTakePrecedence() {
    ServiceLocator locator = link();
    ServiceLocatorUtilities.addOneConstant(locator, GUICE_GREETER, null, Greeter.class);
    assertSame(locator.getService(Greeter.class).getClass(), Named.class);
  }

  @Test
  public void laterDescriptorAddedAroundTheLocatorFailsTheLookup() {
    ServiceLocator locator = link();
    DynamicConfiguration dc = new DynamicConfigurationImpl((ServiceLocatorImpl)locator);
    dc.addActiveDescriptor(BuilderHelper.createConstantDescriptor(GUICE_GREETER, null, Greeter.class));
    dc.commit();
    expectThrows(IllegalStateException.class, () -> locator.getService(Greeter.class));
  }

  @Test
  public void idempotentCommitRegistersOnlyItsFiltersContracts() {
    ServiceLocator locator = link();
    ServiceLocatorUtilities.enablePerThreadScope(locator);
    assertFalse(registered(locator, Plugin.class));
    assertFalse(registered(locator, Unrelated.class));
  }

  @Test
  public void idempotentCommitStillSeesPendingDuplicates() {
    ServiceLocator locator = link();
    DynamicConfiguration dc = locator.getService(DynamicConfigurationService.class).createDynamicConfiguration();
    dc.addIdempotentFilter(BuilderHelper.createContractFilter(Plugin.class.getName()));
    expectThrows(MultiException.class, dc::commit);
    assertFalse(registered(locator, Unrelated.class));
  }
}
