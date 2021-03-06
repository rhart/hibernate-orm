/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.test.cache.infinispan.collection;

import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.hibernate.cache.infinispan.access.AccessDelegate;
import org.hibernate.cache.infinispan.access.NonTxInvalidationCacheAccessDelegate;
import org.hibernate.cache.infinispan.access.PutFromLoadValidator;
import org.hibernate.cache.infinispan.access.TxInvalidationCacheAccessDelegate;
import org.hibernate.cache.infinispan.collection.CollectionRegionImpl;
import org.hibernate.cache.spi.access.CollectionRegionAccessStrategy;
import org.hibernate.engine.spi.SharedSessionContractImplementor;

import org.hibernate.test.cache.infinispan.AbstractRegionAccessStrategyTest;
import org.hibernate.test.cache.infinispan.NodeEnvironment;
import org.hibernate.test.cache.infinispan.util.TestingKeyFactory;
import org.junit.Test;
import junit.framework.AssertionFailedError;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

/**
 * Base class for tests of CollectionRegionAccessStrategy impls.
 *
 * @author Galder Zamarreño
 * @since 3.5
 */
public class CollectionRegionAccessStrategyTest extends
		AbstractRegionAccessStrategyTest<CollectionRegionImpl, CollectionRegionAccessStrategy> {
	protected static int testCount;

	@Override
	protected Object generateNextKey() {
		return TestingKeyFactory.generateCollectionCacheKey( KEY_BASE + testCount++ );
	}

	@Override
	protected CollectionRegionImpl getRegion(NodeEnvironment environment) {
		return environment.getCollectionRegion( REGION_NAME, CACHE_DATA_DESCRIPTION );
	}

	@Override
	protected CollectionRegionAccessStrategy getAccessStrategy(CollectionRegionImpl region) {
		return region.buildAccessStrategy( accessType );
	}

	@Test
	public void testGetRegion() {
		assertEquals( "Correct region", localRegion, localAccessStrategy.getRegion() );
	}

	@Test
	public void testPutFromLoadRemoveDoesNotProduceStaleData() throws Exception {
		if (!cacheMode.isInvalidation()) {
			return;
		}
		final CountDownLatch pferLatch = new CountDownLatch( 1 );
		final CountDownLatch removeLatch = new CountDownLatch( 1 );
		// remove the interceptor inserted by default PutFromLoadValidator, we're using different one
		PutFromLoadValidator originalValidator = PutFromLoadValidator.removeFromCache(localRegion.getCache());
		PutFromLoadValidator mockValidator = spy(originalValidator);
		doAnswer(invocation -> {
			try {
				return invocation.callRealMethod();
			} finally {
				try {
					removeLatch.countDown();
					// the remove should be blocked because the putFromLoad has been acquired
					// and the remove can continue only after we've inserted the entry
					assertFalse(pferLatch.await( 2, TimeUnit.SECONDS ) );
				}
				catch (InterruptedException e) {
					log.debug( "Interrupted" );
					Thread.currentThread().interrupt();
				}
				catch (Exception e) {
					log.error( "Error", e );
					throw new RuntimeException( "Error", e );
				}
			}
		}).when(mockValidator).acquirePutFromLoadLock(any(), any(), anyLong());
		PutFromLoadValidator.addToCache(localRegion.getCache(), mockValidator);

		try {
			final AccessDelegate delegate = localRegion.getCache().getCacheConfiguration().transaction().transactionMode().isTransactional() ?
				new TxInvalidationCacheAccessDelegate(localRegion, mockValidator) :
				new NonTxInvalidationCacheAccessDelegate(localRegion, mockValidator);

			ExecutorService executorService = Executors.newCachedThreadPool();

			final String KEY = "k1";
			Future<Void> pferFuture = executorService.submit(() -> {
				SharedSessionContractImplementor session = mockedSession();
				delegate.putFromLoad(session, KEY, "v1", session.getTimestamp(), null);
				return null;
			});

			Future<Void> removeFuture = executorService.submit(() -> {
				removeLatch.await();
				SharedSessionContractImplementor session = mockedSession();
				withTx(localEnvironment, session, () -> {
					delegate.remove(session, KEY);
					return null;
				});
				pferLatch.countDown();
				return null;
			});

			pferFuture.get();
			removeFuture.get();

			assertFalse(localRegion.getCache().containsKey(KEY));
			assertFalse(remoteRegion.getCache().containsKey(KEY));
		} finally {
			PutFromLoadValidator.removeFromCache(localRegion.getCache());
			PutFromLoadValidator.addToCache(localRegion.getCache(), originalValidator);
		}
	}

	@Test
	public void testPutFromLoad() throws Exception {
		putFromLoadTest(false);
	}

	@Test
	public void testPutFromLoadMinimal() throws Exception {
		putFromLoadTest(true);
	}

	protected void putFromLoadTest(final boolean useMinimalAPI) throws Exception {

		final Object KEY = generateNextKey();

		final CountDownLatch writeLatch1 = new CountDownLatch( 1 );
		final CountDownLatch writeLatch2 = new CountDownLatch( 1 );
		final CountDownLatch completionLatch = new CountDownLatch( 2 );

		Thread node1 = new Thread() {
			@Override
			public void run() {
				try {
					SharedSessionContractImplementor session = mockedSession();
					withTx(localEnvironment, session, () -> {
						assertNull(localAccessStrategy.get(session, KEY, session.getTimestamp()));

						writeLatch1.await();

						if (useMinimalAPI) {
							localAccessStrategy.putFromLoad(session, KEY, VALUE2, session.getTimestamp(), 2, true);
						} else {
							localAccessStrategy.putFromLoad(session, KEY, VALUE2, session.getTimestamp(), 2);
						}
						return null;
					});
				}
				catch (Exception e) {
					log.error( "node1 caught exception", e );
					node1Exception = e;
				}
				catch (AssertionFailedError e) {
					node1Failure = e;
				}
				finally {
					// Let node2 write
					writeLatch2.countDown();
					completionLatch.countDown();
				}
			}
		};

		Thread node2 = new PutFromLoadNode2(KEY, writeLatch1, writeLatch2, useMinimalAPI, completionLatch);

		node1.setDaemon( true );
		node2.setDaemon( true );

		node1.start();
		node2.start();

		assertTrue( "Threads completed", completionLatch.await( 2, TimeUnit.SECONDS ) );

		assertThreadsRanCleanly();

		long txTimestamp = System.currentTimeMillis();

		SharedSessionContractImplementor s1 = mockedSession();
		assertEquals( VALUE2, localAccessStrategy.get(s1, KEY, s1.getTimestamp() ) );
		SharedSessionContractImplementor s2 = mockedSession();
		Object remoteValue = remoteAccessStrategy.get(s2, KEY, s2.getTimestamp());
		if (isUsingInvalidation()) {
			assertEquals( VALUE1, remoteValue);
		}
		else {
			assertEquals( VALUE2, remoteValue);
		}
	}
}
