import assert from 'node:assert/strict';
import test from 'node:test';
import { createReadCache } from '../src/context/readQueries.js';

test('shares pending requests and reuses results for 30 seconds', async (context) => {
    context.mock.timers.enable({ apis: ['Date'], now: 0 });
    const cache = createReadCache();
    const key = 'workshops:[0,12,{}]';
    let reads = 0;
    const read = () => ++reads;
    const pending = cache.load(key, read);
    cache.load(key, read);
    await pending;
    await cache.load(key, read);
    assert.equal(cache.get(key).data, 1);
    context.mock.timers.tick(30_000);
    await cache.load(key, read);
    assert.equal(cache.get(key).data, 2);
    assert.equal(createReadCache().get(key).status, 'idle');
});

test('retries errors and discards pending results invalidated by a write', async () => {
    const cache = createReadCache();
    const key = 'notifications:[0,10,true]';
    const error = new Error('Temporary failure');
    await cache.load(key, () => { throw error; });
    assert.equal(cache.get(key).error, error);

    const stale = Promise.withResolvers();
    const pending = cache.load(key, () => stale.promise, true);
    await cache.load('workshops:[0,3,{}]', () => 'unaffected');
    cache.invalidate('notifications');
    assert.equal(cache.get(key).status, 'idle');
    await cache.load(key, () => 'fresh');
    stale.resolve('stale');
    await pending;
    assert.equal(cache.get(key).data, 'fresh');
    assert.equal(cache.get('workshops:[0,3,{}]').data, 'unaffected');
});
