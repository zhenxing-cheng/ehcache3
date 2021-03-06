/*
 * Copyright Terracotta, Inc.
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

package org.ehcache.impl.internal.store.tiering;

import org.ehcache.config.CacheConfiguration;
import org.ehcache.config.EvictionAdvisor;
import org.ehcache.config.ResourcePools;
import org.ehcache.config.ResourceType;
import org.ehcache.core.internal.store.StoreConfigurationImpl;
import org.ehcache.config.SizedResourcePool;
import org.ehcache.core.spi.service.DiskResourceService;
import org.ehcache.config.units.EntryUnit;
import org.ehcache.config.units.MemoryUnit;
import org.ehcache.CachePersistenceException;
import org.ehcache.expiry.Expirations;
import org.ehcache.expiry.Expiry;
import org.ehcache.impl.internal.concurrent.ConcurrentHashMap;
import org.ehcache.impl.copy.IdentityCopier;
import org.ehcache.core.events.NullStoreEventDispatcher;
import org.ehcache.impl.internal.events.TestStoreEventDispatcher;
import org.ehcache.impl.internal.executor.OnDemandExecutionService;
import org.ehcache.impl.internal.persistence.TestDiskResourceService;
import org.ehcache.impl.internal.sizeof.NoopSizeOfEngine;
import org.ehcache.impl.internal.store.disk.OffHeapDiskStore;
import org.ehcache.impl.internal.store.disk.OffHeapDiskStoreSPITest;
import org.ehcache.impl.internal.store.heap.OnHeapStore;
import org.ehcache.impl.internal.store.heap.OnHeapStoreByValueSPITest;
import org.ehcache.core.spi.time.SystemTimeSource;
import org.ehcache.core.spi.time.TimeSource;
import org.ehcache.impl.serialization.JavaSerializer;
import org.ehcache.internal.store.StoreFactory;
import org.ehcache.internal.store.StoreSPITest;
import org.ehcache.core.internal.service.ServiceLocator;
import org.ehcache.spi.service.ServiceProvider;
import org.ehcache.core.spi.store.Store;
import org.ehcache.core.spi.store.tiering.AuthoritativeTier;
import org.ehcache.core.spi.store.tiering.CachingTier;
import org.ehcache.spi.copy.Copier;
import org.ehcache.spi.serialization.Serializer;
import org.ehcache.core.spi.service.FileBasedPersistenceContext;
import org.ehcache.spi.service.Service;
import org.ehcache.spi.service.ServiceConfiguration;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.ehcache.config.builders.ResourcePoolsBuilder.newResourcePoolsBuilder;
import static org.ehcache.config.units.MemoryUnit.MB;
import static org.ehcache.core.internal.service.ServiceLocator.dependencySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Test the {@link TieredStore} compliance to the
 * {@link Store} contract.
 */

public class TieredStoreSPITest extends StoreSPITest<String, String> {

  private StoreFactory<String, String> storeFactory;
  private final TieredStore.Provider provider = new TieredStore.Provider();
  private final Map<Store<String, String>, String> createdStores = new ConcurrentHashMap<Store<String, String>, String>();

  @Rule
  public final TemporaryFolder folder = new TemporaryFolder();

  @Rule
  public TestDiskResourceService diskResourceService = new TestDiskResourceService();

  @Override
  protected StoreFactory<String, String> getStoreFactory() {
    return storeFactory;
  }

  @Before
  public void setUp() throws IOException {

    storeFactory = new StoreFactory<String, String>() {
      final AtomicInteger aliasCounter = new AtomicInteger();

      @Override
      public Store<String, String> newStore() {
        return newStore(null, null, Expirations.noExpiration(), SystemTimeSource.INSTANCE);
      }

      @Override
      public Store<String, String> newStoreWithCapacity(long capacity) {
        return newStore(capacity, null, Expirations.noExpiration(), SystemTimeSource.INSTANCE);
      }

      @Override
      public Store<String, String> newStoreWithExpiry(Expiry<? super String, ? super String> expiry, TimeSource timeSource) {
        return newStore(null, null, expiry, timeSource);
      }

      @Override
      public Store<String, String> newStoreWithEvictionAdvisor(EvictionAdvisor<String, String> evictionAdvisor) {
        return newStore(null, evictionAdvisor, Expirations.noExpiration(), SystemTimeSource.INSTANCE);
      }

      private Store<String, String> newStore(Long capacity, EvictionAdvisor<String, String> evictionAdvisor, Expiry<? super String, ? super String> expiry, TimeSource timeSource) {
        Serializer<String> keySerializer = new JavaSerializer<String>(getClass().getClassLoader());
        Serializer<String> valueSerializer = new JavaSerializer<String>(getClass().getClassLoader());
        Store.Configuration<String, String> config = new StoreConfigurationImpl<String, String>(getKeyType(), getValueType(),
            evictionAdvisor, getClass().getClassLoader(), expiry, buildResourcePools(capacity), 0, keySerializer, valueSerializer);

        @SuppressWarnings("unchecked")
        final Copier<String> defaultCopier = new IdentityCopier();
        OnHeapStore<String, String> onHeapStore = new OnHeapStore<String, String>(config, timeSource, defaultCopier, defaultCopier, new NoopSizeOfEngine(), NullStoreEventDispatcher.<String, String>nullStoreEventDispatcher());
        try {
          CacheConfiguration cacheConfiguration = mock(CacheConfiguration.class);
          when(cacheConfiguration.getResourcePools()).thenReturn(newResourcePoolsBuilder().disk(1, MB, false).build());
          String spaceName = "alias-" + aliasCounter.getAndIncrement();
          DiskResourceService.PersistenceSpaceIdentifier space = diskResourceService.getPersistenceSpaceIdentifier(spaceName, cacheConfiguration);
          FileBasedPersistenceContext persistenceContext = diskResourceService.createPersistenceContextWithin(space, "store");

          SizedResourcePool diskPool = config.getResourcePools().getPoolForResource(ResourceType.Core.DISK);
          MemoryUnit unit = (MemoryUnit) diskPool.getUnit();

          long sizeInBytes = unit.toBytes(diskPool.getSize());
          OffHeapDiskStore<String, String> diskStore = new OffHeapDiskStore<String, String>(
                  persistenceContext,
                  new OnDemandExecutionService(), null, 1,
                  config, timeSource,
                  new TestStoreEventDispatcher<String, String>(),
                  sizeInBytes);
          TieredStore<String, String> tieredStore = new TieredStore<String, String>(onHeapStore, diskStore);
          provider.registerStore(tieredStore, new CachingTier.Provider() {
            @Override
            public <K, V> CachingTier<K, V> createCachingTier(final Store.Configuration<K, V> storeConfig, final ServiceConfiguration<?>... serviceConfigs) {
              throw new UnsupportedOperationException("Implement me!");
            }

            @Override
            public void releaseCachingTier(final CachingTier<?, ?> resource) {
              OnHeapStoreByValueSPITest.closeStore((OnHeapStore)resource);
            }

            @Override
            public void initCachingTier(final CachingTier<?, ?> resource) {
              // no op
            }

            @Override
            public int rankCachingTier(Set<ResourceType<?>> resourceTypes, Collection<ServiceConfiguration<?>> serviceConfigs) {
              throw new UnsupportedOperationException("Implement me!");
            }

            @Override
            public void start(final ServiceProvider<Service> serviceProvider) {
              throw new UnsupportedOperationException("Implement me!");
            }

            @Override
            public void stop() {
              throw new UnsupportedOperationException("Implement me!");
            }
          }, new AuthoritativeTier.Provider() {
            @Override
            public <K, V> AuthoritativeTier<K, V> createAuthoritativeTier(final Store.Configuration<K, V> storeConfig, final ServiceConfiguration<?>... serviceConfigs) {
              throw new UnsupportedOperationException("Implement me!");
            }

            @Override
            public void releaseAuthoritativeTier(final AuthoritativeTier<?, ?> resource) {
              OffHeapDiskStoreSPITest.closeStore((OffHeapDiskStore<?, ?>)resource);
            }

            @Override
            public void initAuthoritativeTier(final AuthoritativeTier<?, ?> resource) {
              OffHeapDiskStoreSPITest.initStore((OffHeapDiskStore<?, ?>)resource);
            }

            @Override
            public int rankAuthority(ResourceType<?> authorityResource, Collection<ServiceConfiguration<?>> serviceConfigs) {
              throw new UnsupportedOperationException("Implement me!");
            }

            @Override
            public void start(final ServiceProvider<Service> serviceProvider) {
              throw new UnsupportedOperationException("Implement me!");
            }

            @Override
            public void stop() {
              throw new UnsupportedOperationException("Implement me!");
            }
          });
          provider.initStore(tieredStore);
          createdStores.put(tieredStore, spaceName);
          return tieredStore;
        } catch (CachePersistenceException e) {
          throw new RuntimeException("Error creation persistence context", e);
        }
      }

      @Override
      public Store.ValueHolder<String> newValueHolder(final String value) {
        final long creationTime = SystemTimeSource.INSTANCE.getTimeMillis();
        return new Store.ValueHolder<String>() {

          @Override
          public String value() {
            return value;
          }

          @Override
          public long creationTime(TimeUnit unit) {
            return creationTime;
          }

          @Override
          public long expirationTime(TimeUnit unit) {
            return 0;
          }

          @Override
          public boolean isExpired(long expirationTime, TimeUnit unit) {
            return false;
          }

          @Override
          public long lastAccessTime(TimeUnit unit) {
            return 0;
          }

          @Override
          public float hitRate(long now, TimeUnit unit) {
            return 0;
          }

          @Override
          public long hits() {
            throw new UnsupportedOperationException("Implement me!");
          }

          @Override
          public long getId() {
            throw new UnsupportedOperationException("Implement me!");
          }
        };
      }

      @Override
      public Class<String> getKeyType() {
        return String.class;
      }

      @Override
      public Class<String> getValueType() {
        return String.class;
      }

      @Override
      public ServiceConfiguration<?>[] getServiceConfigurations() {
        return new ServiceConfiguration[0];
      }

      @Override
      public String createKey(long seed) {
        return Long.toString(seed);
      }

      @Override
      public String createValue(long seed) {
        char[] chars = new char[400 * 1024];
        Arrays.fill(chars, (char) (0x1 + (seed & 0x7e)));
        return new String(chars);
      }

      @Override
      public void close(final Store<String, String> store) {
        String spaceName = createdStores.get(store);
        provider.releaseStore(store);
        try {
          diskResourceService.destroy(spaceName);
        } catch (CachePersistenceException e) {
          throw new AssertionError(e);
        } finally {
          createdStores.remove(store);
        }
      }

      @Override
      public ServiceProvider<Service> getServiceProvider() {
        ServiceLocator.DependencySet dependencySet = dependencySet();
        dependencySet.with(new FakeCachingTierProvider());
        dependencySet.with(new FakeAuthoritativeTierProvider());
        return dependencySet.build();
      }
    };
  }

  @After
  public void tearDown() throws CachePersistenceException {
    for (Map.Entry<Store<String, String>, String> entry : createdStores.entrySet()) {
      provider.releaseStore(entry.getKey());
      diskResourceService.destroy(entry.getValue());
    }
  }

  private ResourcePools buildResourcePools(Comparable<Long> capacityConstraint) {
    if (capacityConstraint == null) {
      capacityConstraint = 16L;
    }
    return newResourcePoolsBuilder().heap(5, EntryUnit.ENTRIES).disk((Long)capacityConstraint, MB).build();
  }

  public static class FakeCachingTierProvider implements CachingTier.Provider {
    @Override
    @SuppressWarnings("unchecked")
    public <K, V> CachingTier<K, V> createCachingTier(Store.Configuration<K, V> storeConfig, ServiceConfiguration<?>... serviceConfigs) {
      return mock(CachingTier.class);
    }

    @Override
    public void releaseCachingTier(CachingTier<?, ?> resource) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void initCachingTier(CachingTier<?, ?> resource) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int rankCachingTier(Set<ResourceType<?>> resourceTypes, Collection<ServiceConfiguration<?>> serviceConfigs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void start(ServiceProvider<Service> serviceProvider) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void stop() {
      throw new UnsupportedOperationException();
    }
  }

  public static class FakeAuthoritativeTierProvider implements AuthoritativeTier.Provider {
    @Override
    @SuppressWarnings("unchecked")
    public <K, V> AuthoritativeTier<K, V> createAuthoritativeTier(Store.Configuration<K, V> storeConfig, ServiceConfiguration<?>... serviceConfigs) {
      return mock(AuthoritativeTier.class);
    }

    @Override
    public void releaseAuthoritativeTier(AuthoritativeTier<?, ?> resource) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void initAuthoritativeTier(AuthoritativeTier<?, ?> resource) {
      throw new UnsupportedOperationException();
    }

    @Override
    public int rankAuthority(ResourceType<?> authorityResource, Collection<ServiceConfiguration<?>> serviceConfigs) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void start(ServiceProvider<Service> serviceProvider) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void stop() {
      throw new UnsupportedOperationException();
    }
  }
}
