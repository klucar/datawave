package datawave.query.jexl.lookups;

import com.google.common.base.Preconditions;
import com.google.common.collect.Sets;
import datawave.core.iterators.ColumnQualifierRangeIterator;
import datawave.core.iterators.CompositeSeekingIterator;
import datawave.core.iterators.TimeoutExceptionIterator;
import datawave.core.iterators.TimeoutIterator;
import datawave.data.type.DiscreteIndexType;
import datawave.query.Constants;
import datawave.query.config.ShardQueryConfiguration;
import datawave.query.exceptions.DatawaveFatalQueryException;
import datawave.query.exceptions.IllegalRangeArgumentException;
import datawave.query.jexl.LiteralRange;
import datawave.query.tables.ScannerFactory;
import datawave.util.time.DateHelper;
import datawave.webservice.common.logging.ThreadConfigurableLogger;
import datawave.webservice.query.exception.DatawaveErrorCode;
import datawave.webservice.query.exception.NotFoundQueryException;
import datawave.webservice.query.exception.QueryException;
import org.apache.accumulo.core.client.BatchScanner;
import org.apache.accumulo.core.client.IteratorSetting;
import org.apache.accumulo.core.client.TableNotFoundException;
import org.apache.accumulo.core.data.Key;
import org.apache.accumulo.core.data.Range;
import org.apache.accumulo.core.data.Value;
import org.apache.accumulo.core.iterators.user.WholeRowIterator;
import org.apache.hadoop.io.Text;
import org.apache.log4j.Logger;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.Callable;

public class LookupBoundedRangeForTerms extends IndexLookup {
    private static final Logger log = ThreadConfigurableLogger.getLogger(LookupBoundedRangeForTerms.class);
    
    protected Set<String> datatypeFilter;
    private final LiteralRange<?> literalRange;
    
    public LookupBoundedRangeForTerms(LiteralRange<?> literalRange) {
        this.literalRange = literalRange;
        datatypeFilter = Sets.newHashSet();
    }
    
    @Override
    public IndexLookupMap lookup(ShardQueryConfiguration config, ScannerFactory scannerFactory, long maxLookup) {
        String startDay = DateHelper.format(config.getBeginDate());
        String endDay = DateHelper.format(config.getEndDate());
        
        // build the start and end range for the scanner
        // Key for global index is Row-> Normalized FieldValue, CF-> FieldName,
        // CQ->shard_id\x00datatype
        IndexLookupMap fieldToUniqueTerms = new IndexLookupMap(config.getMaxUnfieldedExpansionThreshold(), config.getMaxValueExpansionThreshold());
        
        IteratorSetting fairnessIterator = null;
        if (maxLookup > 0) {
            /**
             * The fairness iterator solves the problem whereby we have runaway iterators as a result of an evaluation that never finds anything
             */
            fairnessIterator = new IteratorSetting(1, TimeoutIterator.class);
            
            long maxTime = maxLookup;
            if (maxTime < Long.MAX_VALUE / 2)
                maxTime *= 2;
            fairnessIterator.addOption(TimeoutIterator.MAX_SESSION_TIME, Long.valueOf(maxTime).toString());
            
        }
        
        String lower = literalRange.getLower().toString(), upper = literalRange.getUpper().toString();
        
        Set<String> fields = Collections.singleton(literalRange.getFieldName());
        Key startKey;
        if (literalRange.isLowerInclusive()) { // inclusive
            startKey = new Key(new Text(lower));
        } else { // non-inclusive
            startKey = new Key(new Text(lower + "\0"));
        }
        
        Key endKey;
        if (literalRange.isUpperInclusive()) {
            // we should have our end key be the end of the range if we are going to use the WRI
            endKey = new Key(new Text(upper), new Text(literalRange.getFieldName()), new Text(endDay + Constants.MAX_UNICODE_STRING));
        } else {
            endKey = new Key(new Text(upper));
        }
        
        Range range = null;
        try {
            range = new Range(startKey, true, endKey, literalRange.isUpperInclusive());
        } catch (IllegalArgumentException e) {
            QueryException qe = new QueryException(DatawaveErrorCode.RANGE_CREATE_ERROR, e, MessageFormat.format("{0}", this.literalRange));
            log.debug(qe);
            throw new IllegalRangeArgumentException(qe);
        }
        
        log.debug("Range: " + range);
        BatchScanner bs = null;
        try {
            bs = scannerFactory.newScanner(config.getIndexTableName(), config.getAuthorizations(), config.getNumQueryThreads(), config.getQuery());
            
            if (log.isDebugEnabled()) {
                log.debug("Range: " + range);
            }
            
            bs.setRanges(Collections.singleton(range));
            bs.fetchColumnFamily(new Text(literalRange.getFieldName()));
            
            if (config.getDatatypeFilter() != null && !config.getDatatypeFilter().isEmpty()) {
                datatypeFilter.addAll(config.getDatatypeFilter());
            }
            
            // set up the GlobalIndexRangeSamplingIterator
            
            IteratorSetting cfg = new IteratorSetting(config.getBaseIteratorPriority() + 50, "WholeRowIterator", WholeRowIterator.class);
            bs.addScanIterator(cfg);
            
            cfg = new IteratorSetting(config.getBaseIteratorPriority() + 48, "DateFilter", ColumnQualifierRangeIterator.class);
            // search from 20YYddMM to 20ZZddMM\uffff to ensure we encompass all of the current day
            String end = endDay + Constants.MAX_UNICODE_STRING;
            cfg.addOption(ColumnQualifierRangeIterator.RANGE_NAME, ColumnQualifierRangeIterator.encodeRange(new Range(startDay, end)));
            
            bs.addScanIterator(cfg);
            
            // If this is a composite field, with multiple terms, we need to setup our query to filter based on each component of the composite range
            if (config.getCompositeToFieldMap().get(literalRange.getFieldName()) != null) {
                
                String compositeSeparator = null;
                if (config.getCompositeFieldSeparators() != null)
                    compositeSeparator = config.getCompositeFieldSeparators().get(literalRange.getFieldName());
                
                if (compositeSeparator != null && (lower.contains(compositeSeparator) || upper.contains(compositeSeparator))) {
                    IteratorSetting compositeIterator = new IteratorSetting(config.getBaseIteratorPriority() + 51, CompositeSeekingIterator.class);
                    
                    compositeIterator.addOption(CompositeSeekingIterator.COMPONENT_FIELDS,
                                    StringUtils.collectionToCommaDelimitedString(config.getCompositeToFieldMap().get(literalRange.getFieldName())));
                    
                    for (String fieldName : config.getCompositeToFieldMap().get(literalRange.getFieldName())) {
                        DiscreteIndexType type = config.getFieldToDiscreteIndexTypes().get(fieldName);
                        if (type != null)
                            compositeIterator.addOption(fieldName + CompositeSeekingIterator.DISCRETE_INDEX_TYPE, type.getClass().getName());
                    }
                    
                    compositeIterator.addOption(CompositeSeekingIterator.SEPARATOR, compositeSeparator);
                    
                    bs.addScanIterator(compositeIterator);
                }
            }
            
            if (null != fairnessIterator) {
                cfg = new IteratorSetting(config.getBaseIteratorPriority() + 100, TimeoutExceptionIterator.class);
                bs.addScanIterator(cfg);
            }
            
            try {
                timedScan(bs.iterator(), fieldToUniqueTerms, config, false, fields, false, maxLookup, null);
            } finally {
                scannerFactory.close(bs);
            }
            
        } catch (TableNotFoundException e) {
            NotFoundQueryException qe = new NotFoundQueryException(DatawaveErrorCode.TABLE_NOT_FOUND, e, MessageFormat.format("Table: {0}",
                            config.getIndexTableName()));
            log.error(qe);
            throw new DatawaveFatalQueryException(qe);
            
        } catch (IOException e) {
            QueryException qe = new QueryException(DatawaveErrorCode.RANGE_CREATE_ERROR, e, MessageFormat.format("{0}", this.literalRange));
            log.debug(qe);
            throw new IllegalRangeArgumentException(qe);
        } finally {
            if (bs != null)
                scannerFactory.close(bs);
        }
        
        if (log.isDebugEnabled()) {
            log.debug("Found " + fieldToUniqueTerms.size() + " matching terms for range: " + fieldToUniqueTerms);
        }
        
        return fieldToUniqueTerms;
    }
    
    @Override
    protected Callable<Boolean> createTimedCallable(final Iterator<Entry<Key,Value>> iter, final IndexLookupMap fieldsToValues, ShardQueryConfiguration config,
                    final boolean unfieldedLookup, final Set<String> fields, boolean isReverse, long timeout) {
        final Set<String> myDatatypeFilter = datatypeFilter;
        return () -> {
            Text holder = new Text();
            try {
                if (log.isTraceEnabled()) {
                    log.trace("Do we have next? " + iter.hasNext());
                    
                }
                while (iter.hasNext()) {
                    
                    Entry<Key,Value> entry = iter.next();
                    
                    if (TimeoutExceptionIterator.exceededTimedValue(entry)) {
                        throw new Exception("Timeout exceeded for bounded range lookup");
                    }
                    Key k = entry.getKey();
                    
                    if (log.isTraceEnabled()) {
                        log.trace("Foward Index entry: " + entry.getKey());
                    }
                    
                    k.getRow(holder);
                    String uniqueTerm = holder.toString();
                    
                    SortedMap<Key,Value> keymap = WholeRowIterator.decodeRow(entry.getKey(), entry.getValue());
                    
                    String field = null;
                    
                    boolean foundDataType = false;
                    
                    for (Key topKey : keymap.keySet()) {
                        if (null == field) {
                            topKey.getColumnFamily(holder);
                            field = holder.toString();
                        }
                        // Get the column qualifier from the key. It
                        // contains the datatype and normalizer class
                        
                        if (null != topKey.getColumnQualifier()) {
                            if (null != myDatatypeFilter && !myDatatypeFilter.isEmpty()) {
                                
                                String colq = topKey.getColumnQualifier().toString();
                                int idx = colq.indexOf(Constants.NULL);
                                
                                if (idx != -1) {
                                    String type = colq.substring(idx + 1);
                                    
                                    // If types are specified and this type
                                    // is not in the list, skip it.
                                    if (myDatatypeFilter.contains(type)) {
                                        
                                        if (log.isTraceEnabled())
                                            log.trace(myDatatypeFilter + " contains " + type);
                                        foundDataType = true;
                                        break;
                                    }
                                    
                                }
                            } else
                                foundDataType = true;
                        }
                    }
                    if (foundDataType) {
                        
                        // obtaining the size of a map can be expensive,
                        // instead
                        // track the count of each unique item added.
                        fieldsToValues.put(field, uniqueTerm);
                        
                        // safety check...
                        Preconditions.checkState(field.equals(literalRange.getFieldName()), "Got an unexpected field name when expanding range" + field + " "
                                        + literalRange.getFieldName());
                        
                        // If this range expands into to many values, we can
                        // stop
                        if (fieldsToValues.get(field).isThresholdExceeded()) {
                            return true;
                            
                        }
                    }
                }
                
            } catch (Exception e) {
                log.info("Failed or timed out expanding range fields: " + e.getMessage());
                if (log.isDebugEnabled())
                    log.debug("Failed or Timed out ", e);
                // Only if not doing an unfielded lookup should we mark all fields as having an exceeded threshold
                if (!unfieldedLookup) {
                    for (String field : fields) {
                        if (log.isTraceEnabled()) {
                            log.trace("field is " + field);
                            log.trace("field is " + (null == fieldsToValues));
                        }
                        fieldsToValues.put(field, "");
                        fieldsToValues.get(field).setThresholdExceeded();
                    }
                } else
                    fieldsToValues.setKeyThresholdExceeded();
                return false;
            }
            
            return true;
        };
    }
    
}
