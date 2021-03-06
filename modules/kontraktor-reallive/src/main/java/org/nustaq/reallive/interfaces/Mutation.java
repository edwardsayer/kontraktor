package org.nustaq.reallive.interfaces;

import org.nustaq.kontraktor.IPromise;

/**
 * Created by moelrue on 05.08.2015.
 */
public interface Mutation<K> {

    IPromise<Boolean> putCAS( RLPredicate<Record<K>> casCondition, K key, Object... keyVals);
    void put(K key, Object... keyVals);
    void addOrUpdate(K key, Object... keyVals);
    void add( K key, Object ... keyVals );
    void add( Record<K> rec );
    void addOrUpdateRec(Record<K> rec);
    void put(Record<K> rec);
    void update( K key, Object ... keyVals );
    void remove(K key);

}
