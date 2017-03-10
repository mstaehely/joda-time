/*
 *  Copyright 2001-2009 Stephen Colebourne
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.joda.time.convert;

import org.checkerframework.checker.index.qual.*;

/**
 * A set of converters, which allows exact converters to be quickly
 * selected. This class is threadsafe because it is (essentially) immutable.
 *
 * @author Brian S O'Neill
 * @since 1.0
 */
class ConverterSet {
    private final Converter [] iConverters;

    // A simple immutable hashtable: closed hashing, linear probing, sized
    // power of 2, at least one null slot.
    private Entry @MinLen(1) [] iSelectEntries;

    ConverterSet(Converter [] converters) {
        // Since this is a package private constructor, we trust ourselves not
        // to alter the array outside this class.
        iConverters = converters;
        iSelectEntries = new Entry[1 << 4]; // 16
    }

    /**
     * Returns the closest matching converter for the given type, or null if
     * none found.
     *
     * @param type type to select, which may be null
     * @throws IllegalStateException if multiple converters match the type
     * equally well
     */

    Converter select(Class<?> type) throws IllegalStateException {
        // Check the hashtable first.
        Entry @MinLen(1) [] entries = iSelectEntries;
        @IndexOrHigh("entries") int length = entries.length;
        // & should preserve IndexFor, so since entries is MinLen(1) and length-1 is IndexFor("entries"),
        // index is IndexFor("entries")
        @SuppressWarnings("index:assignment.type.incompatible") // Unary &
        @IndexFor("entries") int index = type == null ? 0 : type.hashCode() & (length - 1);

        Entry e;
        // This loop depends on there being at least one null slot.
        while ((e = entries[index]) != null) {
            if (e.iType == type) {
                return e.iConverter;
            }
            if (++index >= length) {
                index = 0;
            }
        }

        // Not found in the hashtable, so do actual work.

        Converter converter = selectSlow(this, type);
        e = new Entry(type, converter);

        // Save the entry for future selects. This class must be threadsafe,
        // but there is no synchronization. Since the hashtable is being used
        // as a cache, it is okay to destroy existing entries. This isn't
        // likely to occur unless there is a high amount of concurrency. As
        // time goes on, cache updates will occur less often, and the cache
        // will fill with all the necessary entries.

        // Do all updates on a copy: slots in iSelectEntries must not be
        // updated by multiple threads as this can allow all null slots to be
        // consumed.
        entries = (Entry[])entries.clone();

        // Add new entry.
        entries[index] = e;

        // Verify that at least one null slot exists!
        for (int i=0; i<length; i++) {
            if (entries[i] == null) {
                // Found a null slot, swap in new hashtable.
                iSelectEntries = entries;
                return converter;
            }
        }

        // Double capacity and re-hash.

        @Positive int newLength = length << 1;
        // newLength is positive so newEntries will have length at least 1
        @SuppressWarnings("index:assignment.type.incompatible") // Positive length MinLen(1)
        Entry @MinLen(1) [] newEntries = new Entry[newLength];
        for (int i=0; i<length; i++) {
            e = entries[i];
            type = e.iType;
            // & should preserve IndexFor, so since newEntries is MinLen(1)
            // and newLength-1 is IndexFor("newEntries"),
            // newIndex is IndexFor("entries")
            @SuppressWarnings("index:assignment.type.incompatible") // Unary &
            @IndexFor("newEntries") int newIndex = type == null ? 0 : type.hashCode() & (newLength - 1);
            while (newEntries[newIndex] != null) {
                if (++newIndex >= newLength) {
                    newIndex = 0;
                }
            }
            newEntries[newIndex] = e;
        }

        // Swap in new hashtable.
        iSelectEntries = newEntries;
        return converter;
    }

    /**
     * Returns the amount of converters in the set.
     */
    @NonNegative int size() {
        return iConverters.length;
    }

    /**
     * Copies all the converters in the set to the given array.
     */
    @SuppressWarnings("index:argument.type.incompatible")
    // Variable array lengths
    // Converters is initialized by the caller, using the size() method.
    // As a result, it is guaranteed to be the same size as iConverters.
    void copyInto(Converter [] converters) {
        System.arraycopy(iConverters, 0, converters, 0, iConverters.length);
    }

    /**
     * Returns a copy of this set, with the given converter added. If a
     * matching converter is already in the set, the given converter replaces
     * it. If the converter is exactly the same as one already in the set, the
     * original set is returned.
     *
     * @param converter  converter to add, must not be null
     * @param removed  if not null, element 0 is set to the removed converter
     * @throws NullPointerException if converter is null
     */

    @SuppressWarnings({"index:array.access.unsafe.high", "index:argument.type.incompatible"}) 
    // Index arithmetic
    // Size initialized before array
    //
    // copy has length length + 1, so length is now a valid index for copy
    // As converters and copy both have the same length, this is a
    // compatible argument and safe to pass to the arraycopy method.
    ConverterSet add(Converter converter, Converter @MinLen(1) [] removed) {
        Converter [] converters = iConverters;
        int length = converters.length;

        for (int i=0; i<length; i++) {
            Converter existing = converters[i];
            if (converter.equals(existing)) {
                // Already in the set.
                if (removed != null) {
                    removed[0] = null;
                }
                return this;
            }
            
            if (converter.getSupportedType() == existing.getSupportedType()) {
                // Replace the converter.
                Converter [] copy = new Converter[length];
                    
                for (int j=0; j<length; j++) {
                    if (j != i) {
                        copy[j] = converters[j];
                    } else {
                        copy[j] = converter;
                    }
                }

                if (removed != null) {
                    removed[0] = existing;
                }
                return new ConverterSet(copy);
            }
        }

        // Not found, so add it.
        Converter [] copy = new Converter[length + 1];
        System.arraycopy(converters, 0, copy, 0, length);
        copy[length] = converter; // Warning here that length is not a valid index for copy
        
        if (removed != null) {
            removed[0] = null;
        }
        return new ConverterSet(copy);
    }

    /**
     * Returns a copy of this set, with the given converter removed. If the
     * converter was not in the set, the original set is returned.
     *
     * @param converter  converter to remove, must not be null
     * @param removed  if not null, element 0 is set to the removed converter
     * @throws NullPointerException if converter is null
     */

    ConverterSet remove(Converter converter, Converter @MinLen(1) [] removed) {
        Converter [] converters = iConverters;
        int length = converters.length;

        for (int i=0; i<length; i++) {
            if (converter.equals(converters[i])) {
                return remove(i, removed);
            }
        }

        // Not found.
        if (removed != null) {
            removed[0] = null;
        }
        return this;
    }

    /**
     * Returns a copy of this set, with the converter at the given index
     * removed.
     *
     * @param index index of converter to remove
     * @param removed if not null, element 0 is set to the removed converter
     * @throws IndexOutOfBoundsException if the index is invalid
     */

    @SuppressWarnings({"index:array.access.unsafe.high"}) 
    // Index arithmetic
    // Because this is removing a value from the array, j++ will always be 
    // valid if it is reached, thanks to i != index.
    ConverterSet remove(final @NonNegative int index, Converter @MinLen(1) [] removed) {
        Converter [] converters = iConverters;
        int length = converters.length;
        if (index >= length) {
            throw new IndexOutOfBoundsException();
        }

        if (removed != null) {
            removed[0] = converters[index];
        }

        Converter [] copy = new Converter[length - 1];
                
        int j = 0;
        for (int i=0; i<length; i++) {
            if (i != index) {
                copy[j++] = converters[i]; // Warning here that j++ is not a valid index for copy
            }
        }
        
        return new ConverterSet(copy);
    }

    /**
     * Returns the closest matching converter for the given type, but not very
     * efficiently.
     */

    // In the eliminate supertypes loop, we never remove all of the
    // availiable converters, so length - 1 is NonNegative
    @SuppressWarnings("index:assignment.type.incompatible") 
    // Index arithmetic
    private static Converter selectSlow(ConverterSet set, Class<?> type) {
        Converter [] converters = set.iConverters;
        @IndexOrHigh("converters") int length = converters.length;
        Converter converter;

        for (@NonNegative int i=length; --i>=0; ) {
            converter = converters[i];
            Class<?> supportedType = converter.getSupportedType();

            if (supportedType == type) {
                // Exact match.
                return converter;
            }

            if (supportedType == null || (type != null && !supportedType.isAssignableFrom(type))) {
                // Eliminate the impossible.
                set = set.remove(i, null);
                converters = set.iConverters;
                length = converters.length;
            }
        }

        // Haven't found exact match, so check what remains in the set.

        if (type == null || length == 0) {
            return null;
        }
        if (length == 1) {
            // Since length is converters.length, this holds
            assert converters.length >= 1 : "@AssumeAssertion(index)"; // Size initialized before array
            // Found the one best match.
            return converters[0];
        }

        // Eliminate supertypes.
        for (@NonNegative int i=length; --i>=0; ) {
            converter = converters[i];
            Class<?> supportedType = converter.getSupportedType();
            for (@NonNegative int j=length; --j>=0; ) {
                if (j != i && converters[j].getSupportedType().isAssignableFrom(supportedType)) {
                    // Eliminate supertype.
                    set = set.remove(j, null);
                    converters = set.iConverters;
                    length = converters.length;
                    i = length - 1; // Warning here
                }
            }
        }        
        
        // Check what remains in the set.

        if (length == 1) {
            // Since length is converters.length, this holds
            assert converters.length >= 1 : "@AssumeAssertion(index)"; // Size initialized before array
            // Found the one best match.
            return converters[0];
        }

        // Class c implements a, b {}
        // Converters exist only for a and b. Which is better? Neither.

        StringBuilder msg = new StringBuilder();
        msg.append("Unable to find best converter for type \"");
        msg.append(type.getName());
        msg.append("\" from remaining set: ");
        for (int i=0; i<length; i++) {
            converter = converters[i];
            Class<?> supportedType = converter.getSupportedType();

            msg.append(converter.getClass().getName());
            msg.append('[');
            msg.append(supportedType == null ? null : supportedType.getName());
            msg.append("], ");
        }

        throw new IllegalStateException(msg.toString());
    }

    static class Entry {
        final Class<?> iType;
        final Converter iConverter;

        Entry(Class<?> type, Converter converter) {
            iType = type;
            iConverter = converter;
        }
    }

}
