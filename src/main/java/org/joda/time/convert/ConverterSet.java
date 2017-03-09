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
    private final Converter @Positive [] iConverters;

    // A simple immutable hashtable: closed hashing, linear probing, sized
    // power of 2, at least one null slot.
    private Entry @Positive [] iSelectEntries;

    ConverterSet(Converter @Positive [] converters) {
        // Since this is a package private constructor, we trust ourselves not
        // to alter the array outside this class.
        iConverters = converters;
        iSelectEntries = new Entry @Positive [1 << 4]; // 16
    }

    /**
     * Returns the closest matching converter for the given type, or null if
     * none found.
     *
     * @param type type to select, which may be null
     * @throws IllegalStateException if multiple converters match the type
     * equally well
     */

    @SuppressWarnings({"index:assignment.type.incompatible", "index:array.access.unsafe.high"})
        // Size initialized before array
        // Cannot initialize index as a valid index for newEntries, as that
        // array does not exist yet. 
    Converter select(Class<?> type) throws IllegalStateException {
        // Check the hashtable first.
        Entry @Positive [] entries = iSelectEntries;
        @IndexFor("entries") int length = entries.length;
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

        int newLength = length << 1;
        Entry @Positive [] newEntries = new Entry @Positive [newLength];
        for (int i=0; i<length; i++) {
            e = entries[i];
            type = e.iType;
            index = type == null ? 0 : type.hashCode() & (newLength - 1);
            while (newEntries[index] != null) {
                if (++index >= newLength) {
                    index = 0;
                }
            }
            newEntries[index] = e;
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
    // Annotating converters as having the same length as iConverters
    // does not get rid of the incompatible argument.
    void copyInto(Converter @Positive [] converters) {
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
    // Potential null arrays
    // High index waring is because checker can't validate removed[0], which
    // should be unreachable unless removed exists. Incompatible argument
    // is a result of being unable to annotate copy as having the same
    // length as iConverters, because it may end up one longer.
    ConverterSet add(Converter converter, Converter @Positive [] removed) {
        Converter @Positive [] converters = iConverters;
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
                Converter @Positive [] copy = new Converter @Positive [length];
                    
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
        Converter @Positive [] copy = new Converter @Positive [length + 1];
        System.arraycopy(converters, 0, copy, 0, length);
        copy[length] = converter;
        
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

    @SuppressWarnings("index:array.access.unsafe.high") 
    // Potential null arrays
    // Won't reach removed unless the array exists.
    ConverterSet remove(Converter converter, Converter @Positive [] removed) {
        Converter @Positive [] converters = iConverters;
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
    // valid if it is reached, thanks to i != index.. Also, should never be
    // able to reach removed[0] unless removed exists, and so has positive 
    // length
    ConverterSet remove(final @NonNegative int index, Converter @Positive [] removed) {
        Converter @Positive [] converters = iConverters;
        int length = converters.length;
        if (index >= length) {
            throw new IndexOutOfBoundsException();
        }

        if (removed != null) {
            removed[0] = converters[index];
        }

        Converter @Positive [] copy = new Converter @Positive [length - 1];
                
        int j = 0;
        for (int i=0; i<length; i++) {
            if (i != index) {
                copy[j++] = converters[i];
            }
        }
        
        return new ConverterSet(copy);
    }

    /**
     * Returns the closest matching converter for the given type, but not very
     * efficiently.
     */

    @SuppressWarnings({"index:assignment.type.incompatible", "index:array.access.unsafe.high"}) 
        // Index arithmetic
        // Length -1 should never be negative, as length of an array will 
        // need to be >= 1. Converters[i] is used after i is decremented, 
        // so it cannot be too high. Converters[j] is used after j is
        // decremented, so it cannot be too high.
    private static Converter selectSlow(ConverterSet set, Class<?> type) {
        Converter @Positive [] converters = set.iConverters;
        int length = converters.length;
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
            // Found the one best match.
            return converters[0];
        }

        // At this point, there exist multiple potential converters.

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
                    i = length - 1;
                }
            }
        }        
        
        // Check what remains in the set.

        if (length == 1) {
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
