import { createContext, useCallback, useContext, useEffect, useSyncExternalStore } from 'react';

export const ReadQueriesContext = createContext(null);

const FRESH_FOR_MS = 30_000;
const MAX_ENTRIES = 64;
const idle = Object.freeze({ status: 'idle' });

export function createReadCache() {
    const entries = new Map();
    const listeners = new Map();

    const publish = (key, entry) => {
        entries.set(key, entry);
        listeners.get(key)?.forEach((listener) => listener());
    };

    return {
        get: (key) => entries.get(key) ?? idle,
        subscribe(key, listener) {
            const group = listeners.get(key) ?? new Set();
            listeners.set(key, group);
            group.add(listener);
            return () => {
                group.delete(listener);
                if (!group.size) listeners.delete(key);
            };
        },
        load(key, read, force = false) {
            const current = entries.get(key);
            if (current?.status === 'loading'
                || (!force && current?.status === 'ready' && Date.now() < current.expiresAt)) return;

            if (entries.size >= MAX_ENTRIES) {
                const removable = [...entries.keys()].find((entryKey) => !listeners.has(entryKey));
                if (removable !== undefined) entries.delete(removable);
            }
            const pending = { status: 'loading' };
            publish(key, pending);
            return Promise.resolve().then(read).then(
                (data) => {
                    if (entries.get(key) === pending) {
                        publish(key, { status: 'ready', data, expiresAt: Date.now() + FRESH_FOR_MS });
                    }
                },
                (error) => {
                    if (entries.get(key) === pending) publish(key, { status: 'error', error });
                },
            );
        },
        invalidate(...resources) {
            for (const key of entries.keys()) {
                if (!resources.includes(key.split(':', 1)[0])) continue;
                entries.delete(key);
                listeners.get(key)?.forEach((listener) => listener());
            }
        },
    };
}

export function useReadCache() {
    return useContext(ReadQueriesContext);
}

export function useReadQuery(resource, params, read) {
    const cache = useReadCache();
    const key = `${resource}:${JSON.stringify(params)}`;
    const subscribe = useCallback((callback) => cache.subscribe(key, callback), [cache, key]);
    const snapshot = useCallback(() => cache.get(key), [cache, key]);
    const retry = useCallback(() => cache.load(key, read, true), [cache, key, read]);
    const state = useSyncExternalStore(subscribe, snapshot);

    useEffect(() => {
        cache.load(key, read);
    }, [cache, key, read]);

    // A mounted query reloads when a successful write invalidates its entry.
    useEffect(() => {
        if (state === idle) cache.load(key, read);
    }, [cache, key, read, state]);

    return {
        data: state.data,
        error: state.error,
        loading: state === idle || state.status === 'loading',
        retry,
    };
}
