package org.nustaq.reallive.api;

/**
 * Created by moelrue on 05.08.2015.
 */
public interface Mutation<K,V> {

    void addOrUpdate( K key, Object ... keyVals );
    void add( K key, Object ... keyVals );
    void update( K key, Object ... keyVals);
    void remove(K key);

}
