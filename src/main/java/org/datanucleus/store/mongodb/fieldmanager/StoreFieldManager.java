/**********************************************************************
Copyright (c) 2011 Andy Jefferson. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors :
    ...
***********************************************************************/
package org.datanucleus.store.mongodb.fieldmanager;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.identity.IdentityUtils;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.DiscriminatorMetaData;
import org.datanucleus.metadata.DiscriminatorStrategy;
import org.datanucleus.metadata.FieldRole;
import org.datanucleus.metadata.IdentityStrategy;
import org.datanucleus.metadata.MetaDataUtils;
import org.datanucleus.metadata.RelationType;
import org.datanucleus.state.ObjectProvider;
import org.datanucleus.store.StoreManager;
import org.datanucleus.store.exceptions.ReachableObjectNotCascadedException;
import org.datanucleus.store.fieldmanager.AbstractStoreFieldManager;
import org.datanucleus.store.fieldmanager.FieldManager;
import org.datanucleus.store.mongodb.MongoDBUtils;
import org.datanucleus.store.schema.naming.ColumnType;
import org.datanucleus.store.schema.table.MemberColumnMapping;
import org.datanucleus.store.schema.table.Table;
import org.datanucleus.store.types.SCOUtils;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;

/**
 * Field Manager for putting values into MongoDB.
 */
public class StoreFieldManager extends AbstractStoreFieldManager
{
    protected Table table;

    protected DBObject dbObject;

    /** Metadata of the owner field if this is for an embedded object. */
    protected AbstractMemberMetaData ownerMmd = null;

    public StoreFieldManager(ObjectProvider op, DBObject dbObject, boolean insert, Table table)
    {
        super(op, insert);
        this.table = table;
        this.dbObject = dbObject;
    }

    protected MemberColumnMapping getColumnMapping(int fieldNumber)
    {
        return table.getMemberColumnMappingForMember(cmd.getMetaDataForManagedMemberAtAbsolutePosition(fieldNumber));
    }

    @Override
    public void storeBooleanField(int fieldNumber, boolean value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        MemberColumnMapping mapping = getColumnMapping(fieldNumber);
        dbObject.put(mapping.getColumn(0).getName(), Boolean.valueOf(value));
    }

    @Override
    public void storeCharField(int fieldNumber, char value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        MemberColumnMapping mapping = getColumnMapping(fieldNumber);
        dbObject.put(mapping.getColumn(0).getName(), "" + value);
    }

    @Override
    public void storeByteField(int fieldNumber, byte value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        MemberColumnMapping mapping = getColumnMapping(fieldNumber);
        dbObject.put(mapping.getColumn(0).getName(), Byte.valueOf(value));
    }

    @Override
    public void storeShortField(int fieldNumber, short value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        MemberColumnMapping mapping = getColumnMapping(fieldNumber);
        dbObject.put(mapping.getColumn(0).getName(), Short.valueOf(value));
    }

    @Override
    public void storeIntField(int fieldNumber, int value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        MemberColumnMapping mapping = getColumnMapping(fieldNumber);
        dbObject.put(mapping.getColumn(0).getName(), Integer.valueOf(value));
    }

    @Override
    public void storeLongField(int fieldNumber, long value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        MemberColumnMapping mapping = getColumnMapping(fieldNumber);
        dbObject.put(mapping.getColumn(0).getName(), Long.valueOf(value));
    }

    @Override
    public void storeFloatField(int fieldNumber, float value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        MemberColumnMapping mapping = getColumnMapping(fieldNumber);
        dbObject.put(mapping.getColumn(0).getName(), Float.valueOf(value));
    }

    @Override
    public void storeDoubleField(int fieldNumber, double value)
    {
        if (!isStorable(fieldNumber))
        {
            return;
        }
        MemberColumnMapping mapping = getColumnMapping(fieldNumber);
        dbObject.put(mapping.getColumn(0).getName(), Double.valueOf(value));
    }

    @Override
    public void storeStringField(int fieldNumber, String value)
    {
        AbstractMemberMetaData mmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(fieldNumber);
        if (!isStorable(mmd))
        {
            return;
        }

        if (mmd.getValueStrategy() == IdentityStrategy.IDENTITY)
        {
            // Using "_id" to represent this field so don't put it
            return;
        }

        MemberColumnMapping mapping = getColumnMapping(fieldNumber);
        String fieldName = mapping.getColumn(0).getName();
        if (value == null)
        {
            dbObject.removeField(fieldName);
            return;
        }

        dbObject.put(fieldName, value);
    }

    @Override
    public void storeObjectField(int fieldNumber, Object value)
    {
        AbstractMemberMetaData mmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(fieldNumber);
        if (!isStorable(mmd))
        {
            return;
        }

        ExecutionContext ec = op.getExecutionContext();
        MemberColumnMapping mapping = getColumnMapping(fieldNumber);

        ClassLoaderResolver clr = ec.getClassLoaderResolver();
        RelationType relationType = mmd.getRelationType(clr);
        StoreManager storeMgr = ec.getStoreManager();
        if (relationType != RelationType.NONE && MetaDataUtils.getInstance().isMemberEmbedded(ec.getMetaDataManager(), clr, mmd, relationType, ownerMmd))
        {
            // Embedded field
            if (RelationType.isRelationSingleValued(relationType))
            {
                if ((insert && !mmd.isCascadePersist()) || (!insert && !mmd.isCascadeUpdate()))
                {
                    if (!ec.getApiAdapter().isDetached(value) && !ec.getApiAdapter().isPersistent(value))
                    {
                        // Related PC object not persistent, but cant do cascade-persist so throw exception
                        if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                        {
                            NucleusLogger.PERSISTENCE.debug(Localiser.msg("007006", mmd.getFullFieldName()));
                        }
                        throw new ReachableObjectNotCascadedException(mmd.getFullFieldName(), value);
                    }
                }

                // Embedded PC object - can be stored nested in the BSON doc (default), or flat
                boolean nested = MongoDBUtils.isMemberNested(mmd);

                if (nested && ownerMmd != null)
                {
                    if (RelationType.isBidirectional(relationType))
                    {
                        // Field has mapped-by, so just use that
                        if ((ownerMmd.getMappedBy() != null && mmd.getName().equals(ownerMmd.getMappedBy())) ||
                                (mmd.getMappedBy() != null && ownerMmd.getName().equals(mmd.getMappedBy())))
                        {
                            // Other side of owner bidirectional, so omit
                            return;
                        }
                    }
                    else 
                    {
                        // mapped-by not set but could have owner-field
                        if (ownerMmd.hasCollection())
                        {
                            if (ownerMmd.getElementMetaData().getEmbeddedMetaData() != null &&
                                    ownerMmd.getElementMetaData().getEmbeddedMetaData().getOwnerMember() != null &&
                                    ownerMmd.getElementMetaData().getEmbeddedMetaData().getOwnerMember().equals(mmd.getName()))
                            {
                                // This is the owner-field linking back to the owning object so stop
                                return;
                            }
                        }
                        else if (ownerMmd.getEmbeddedMetaData() != null &&
                                ownerMmd.getEmbeddedMetaData().getOwnerMember() != null &&
                                ownerMmd.getEmbeddedMetaData().getOwnerMember().equals(mmd.getName()))
                        {
                            // This is the owner-field linking back to the owning object so stop
                            return;
                        }
                    }
                }

                if (value == null)
                {
                    if (nested)
                    {
                        for (int i=0;i<mapping.getNumberOfColumns();i++)
                        {
                            dbObject.removeField(mapping.getColumn(i).getName());
                        }
                        return;
                    }

                    // TODO Delete any fields for the embedded object (see Cassandra for example)
                    return;
                }

                AbstractClassMetaData embcmd = ec.getMetaDataManager().getMetaDataForClass(value.getClass(), clr);
                if (embcmd == null)
                {
                    throw new NucleusUserException("Field " + mmd.getFullFieldName() +
                        " specified as embedded but metadata not found for the class of type " + mmd.getTypeName());
                }

                ObjectProvider embOP = ec.findObjectProviderForEmbedded(value, op, mmd);
                DBObject embeddedObject = dbObject;
                if (nested)
                {
                    // Nested, so create nested object that we will store the embedded object in
                    embeddedObject = new BasicDBObject();
                }

                if (embcmd.hasDiscriminatorStrategy())
                {
                    // Discriminator for embedded object
                    String discPropName = null;
                    if (mmd.getEmbeddedMetaData() != null && mmd.getEmbeddedMetaData().getDiscriminatorMetaData() != null)
                    {
                        discPropName = mmd.getEmbeddedMetaData().getDiscriminatorMetaData().getColumnName();
                    }
                    else
                    {
                        discPropName = storeMgr.getNamingFactory().getColumnName(embcmd, ColumnType.DISCRIMINATOR_COLUMN); // TODO Use Table
                    }
                    DiscriminatorMetaData discmd = embcmd.getDiscriminatorMetaData();
                    String discVal = null;
                    if (embcmd.getDiscriminatorStrategy() == DiscriminatorStrategy.CLASS_NAME)
                    {
                        discVal = embcmd.getFullClassName();
                    }
                    else
                    {
                        discVal = discmd.getValue();
                    }
                    embeddedObject.put(discPropName, discVal);
                }

                List<AbstractMemberMetaData> embMmds = new ArrayList<AbstractMemberMetaData>();
                embMmds.add(mmd);

                FieldManager ffm = new StoreEmbeddedFieldManager(embOP, embeddedObject, insert, embMmds, table);
                embOP.provideFields(embcmd.getAllMemberPositions(), ffm);

                if (nested)
                {
                    // Nested embedding, as nested document
                    dbObject.put(mapping.getColumn(0).getName(), embeddedObject);
                }
                return;
            }
            else if (RelationType.isRelationMultiValued(relationType))
            {
                // Embedded collection/map/array - stored nested
                if (value == null)
                {
                    for (int i=0;i<mapping.getNumberOfColumns();i++)
                    {
                        dbObject.removeField(mapping.getColumn(i).getName());
                    }
                    return;
                }

                if (mmd.hasCollection())
                {
                    AbstractClassMetaData embcmd = mmd.getCollection().getElementClassMetaData(clr, ec.getMetaDataManager());
                    Collection coll = new ArrayList();
                    Collection valueColl = (Collection)value;
                    Iterator collIter = valueColl.iterator();
                    while (collIter.hasNext())
                    {
                        Object element = collIter.next();
                        if (!element.getClass().getName().equals(embcmd.getFullClassName()))
                        {
                            // Inherited object
                            embcmd = ec.getMetaDataManager().getMetaDataForClass(element.getClass(), clr);
                        }

                        BasicDBObject embeddedObject = new BasicDBObject();
                        if (embcmd.hasDiscriminatorStrategy())
                        {
                            // Discriminator for embedded object
                            String discPropName = null;
                            if (mmd.getEmbeddedMetaData() != null && mmd.getEmbeddedMetaData().getDiscriminatorMetaData() != null)
                            {
                                discPropName = mmd.getEmbeddedMetaData().getDiscriminatorMetaData().getColumnName();
                            }
                            else
                            {
                                discPropName = storeMgr.getNamingFactory().getColumnName(embcmd, ColumnType.DISCRIMINATOR_COLUMN); // TODO Use Table
                            }
                            DiscriminatorMetaData discmd = embcmd.getDiscriminatorMetaData();
                            String discVal = null;
                            if (embcmd.getDiscriminatorStrategy() == DiscriminatorStrategy.CLASS_NAME)
                            {
                                discVal = embcmd.getFullClassName();
                            }
                            else
                            {
                                discVal = discmd.getValue();
                            }
                            embeddedObject.put(discPropName, discVal);
                        }

                        ObjectProvider embOP = ec.findObjectProviderForEmbedded(element, op, mmd);
                        embOP.setPcObjectType(ObjectProvider.EMBEDDED_COLLECTION_ELEMENT_PC);
                        String embClassName = embOP.getClassMetaData().getFullClassName();
                        if (!storeMgr.managesClass(embClassName))
                        {
                            storeMgr.manageClasses(clr, embClassName);
                        }
                        Table elemTable = storeMgr.getStoreDataForClass(embClassName).getTable();
                        StoreFieldManager sfm = new StoreFieldManager(embOP, embeddedObject, insert, elemTable);
                        sfm.ownerMmd = mmd;
                        embOP.provideFields(embcmd.getAllMemberPositions(), sfm);
                        coll.add(embeddedObject);
                    }
                    dbObject.put(mapping.getColumn(0).getName(), coll); // Store as List<DBObject>
                    return;
                }
                else if (mmd.hasArray())
                {
                    AbstractClassMetaData embcmd = mmd.getArray().getElementClassMetaData(clr, ec.getMetaDataManager());
                    Object[] array = new Object[Array.getLength(value)];
                    for (int i=0;i<array.length;i++)
                    {
                        Object element = Array.get(value, i);
                        if (!element.getClass().getName().equals(embcmd.getFullClassName()))
                        {
                            // Inherited object
                            embcmd = ec.getMetaDataManager().getMetaDataForClass(element.getClass(), clr);
                        }

                        BasicDBObject embeddedObject = new BasicDBObject();
                        if (embcmd.hasDiscriminatorStrategy())
                        {
                            // Discriminator for embedded object
                            String discPropName = null;
                            if (mmd.getEmbeddedMetaData() != null && mmd.getEmbeddedMetaData().getDiscriminatorMetaData() != null)
                            {
                                discPropName = mmd.getEmbeddedMetaData().getDiscriminatorMetaData().getColumnName();
                            }
                            else
                            {
                                discPropName = storeMgr.getNamingFactory().getColumnName(embcmd, ColumnType.DISCRIMINATOR_COLUMN); // TODO Use Table
                            }
                            DiscriminatorMetaData discmd = embcmd.getDiscriminatorMetaData();
                            String discVal = null;
                            if (embcmd.getDiscriminatorStrategy() == DiscriminatorStrategy.CLASS_NAME)
                            {
                                discVal = embcmd.getFullClassName();
                            }
                            else
                            {
                                discVal = discmd.getValue();
                            }
                            embeddedObject.put(discPropName, discVal);
                        }

                        ObjectProvider embOP = ec.findObjectProviderForEmbedded(element, op, mmd);
                        embOP.setPcObjectType(ObjectProvider.EMBEDDED_COLLECTION_ELEMENT_PC);
                        String embClassName = embOP.getClassMetaData().getFullClassName();
                        if (!storeMgr.managesClass(embClassName))
                        {
                            storeMgr.manageClasses(clr, embClassName);
                        }
                        Table elemTable = op.getStoreManager().getStoreDataForClass(embClassName).getTable();
                        StoreFieldManager sfm = new StoreFieldManager(embOP, embeddedObject, insert, elemTable);
                        sfm.ownerMmd = mmd;
                        embOP.provideFields(embcmd.getAllMemberPositions(), sfm);
                        array[i] = embeddedObject;
                    }
                    dbObject.put(mapping.getColumn(0).getName(), array); // Store as DBObject[]
                    return;
                }
                else
                {
                    AbstractClassMetaData keyCmd = mmd.getMap().getKeyClassMetaData(clr, ec.getMetaDataManager());
                    AbstractClassMetaData valCmd = mmd.getMap().getValueClassMetaData(clr, ec.getMetaDataManager());

                    // TODO Allow for inherited keys/values and discriminator
                    Collection entryList = new ArrayList();
                    Map valueMap = (Map)value;
                    Iterator<Map.Entry> mapEntryIter = valueMap.entrySet().iterator();
                    while (mapEntryIter.hasNext())
                    {
                        Map.Entry entry = mapEntryIter.next();
                        BasicDBObject entryObj = new BasicDBObject();

                        if (keyCmd == null)
                        {
                            processContainerNonRelationField("key", ec, entry.getKey(), entryObj, mmd, FieldRole.ROLE_MAP_KEY);
                        }
                        else
                        {
                            ObjectProvider embOP = ec.findObjectProviderForEmbedded(entry.getKey(), op, mmd);
                            embOP.setPcObjectType(ObjectProvider.EMBEDDED_MAP_KEY_PC);
                            BasicDBObject embeddedKey = new BasicDBObject();
                            String keyClassName = embOP.getClassMetaData().getFullClassName();
                            if (!storeMgr.managesClass(keyClassName))
                            {
                                storeMgr.manageClasses(clr, keyClassName);
                            }
                            Table keyTable = op.getStoreManager().getStoreDataForClass(keyClassName).getTable();
                            StoreFieldManager sfm = new StoreFieldManager(embOP, embeddedKey, insert, keyTable);
                            sfm.ownerMmd = mmd;
                            embOP.provideFields(keyCmd.getAllMemberPositions(), sfm);
                            entryObj.append("key", embeddedKey);
                        }

                        if (valCmd == null)
                        {
                            processContainerNonRelationField("value", ec, entry.getValue(), entryObj, mmd, FieldRole.ROLE_MAP_VALUE);
                        }
                        else
                        {
                            ObjectProvider embOP = ec.findObjectProviderForEmbedded(entry.getValue(), op, mmd);
                            embOP.setPcObjectType(ObjectProvider.EMBEDDED_MAP_VALUE_PC);
                            BasicDBObject embeddedVal = new BasicDBObject();
                            String valClassName = embOP.getClassMetaData().getFullClassName();
                            if (!storeMgr.managesClass(valClassName))
                            {
                                storeMgr.manageClasses(clr, valClassName);
                            }
                            Table valTable = op.getStoreManager().getStoreDataForClass(valClassName).getTable();
                            StoreFieldManager sfm = new StoreFieldManager(embOP, embeddedVal, insert, valTable);
                            sfm.ownerMmd = mmd;
                            embOP.provideFields(valCmd.getAllMemberPositions(), sfm);
                            entryObj.append("value", embeddedVal);
                        }
                        entryList.add(entryObj);
                    }
                    dbObject.put(mapping.getColumn(0).getName(), entryList);
                    return;
                }
            }
        }

        storeNonEmbeddedObjectField(mmd, relationType, clr, value);
    }

    protected void storeNonEmbeddedObjectField(AbstractMemberMetaData mmd, RelationType relationType, ClassLoaderResolver clr, Object value)
    {
        int fieldNumber = mmd.getAbsoluteFieldNumber();
        ExecutionContext ec = op.getExecutionContext();
        MemberColumnMapping mapping = getColumnMapping(fieldNumber);

        if (value == null)
        {
            for (int i=0;i<mapping.getNumberOfColumns();i++)
            {
                String colName = mapping.getColumn(i).getName();
                if (dbObject.containsField(colName))
                {
                    dbObject.removeField(colName);
                }
            }
            return;
        }

        if (mmd.isSerialized())
        {
            // TODO Allow other types of serialisation
            byte[] bytes = MongoDBUtils.getStoredValueForJavaSerialisedField(mmd, value);
            dbObject.put(mapping.getColumn(0).getName(), bytes);
            SCOUtils.wrapSCOField(op, fieldNumber, value, true);
        }
        else if (RelationType.isRelationSingleValued(relationType))
        {
            // PC object, so make sure it is persisted
            if ((insert && !mmd.isCascadePersist()) || (!insert && !mmd.isCascadeUpdate()))
            {
                if (!ec.getApiAdapter().isDetached(value) && !ec.getApiAdapter().isPersistent(value))
                {
                    // Related PC object not persistent, but cant do cascade-persist so throw exception
                    if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                    {
                        NucleusLogger.PERSISTENCE.debug(Localiser.msg("007006", mmd.getFullFieldName()));
                    }
                    throw new ReachableObjectNotCascadedException(mmd.getFullFieldName(), value);
                }
            }

            processSingleRelationField(value, ec, mapping.getColumn(0).getName());
        }
        else if (RelationType.isRelationMultiValued(relationType))
        {
            // Collection/Map/Array
            processContainerRelationField(mmd, value, ec, mapping.getColumn(0).getName());
            SCOUtils.wrapSCOField(op, fieldNumber, value, true);
        }
        else
        {
            if (mapping.getTypeConverter() != null)
            {
                // Persist using the provided converter
                Object datastoreValue = mapping.getTypeConverter().toDatastoreType(value);
                if (mapping.getNumberOfColumns() > 1)
                {
                    for (int i=0;i<mapping.getNumberOfColumns();i++)
                    {
                        // TODO Persist as the correct column type since the typeConverter type may not be directly persistable
                        Object colValue = Array.get(datastoreValue, i);
                        dbObject.put(mapping.getColumn(i).getName(), colValue);
                    }
                }
                else
                {
                    dbObject.put(mapping.getColumn(0).getName(), datastoreValue);
                }
            }
            else
            {
                processContainerNonRelationField(mapping.getColumn(0).getName(), ec, value, dbObject, mmd, FieldRole.ROLE_FIELD);
            }
            SCOUtils.wrapSCOField(op, fieldNumber, value, true);
        }
    }

    protected void processSingleRelationField(Object value, ExecutionContext ec, String fieldName)
    {
        Object valuePC = op.getExecutionContext().persistObjectInternal(value, null, -1, -1);
        Object valueId = ec.getApiAdapter().getIdForObject(valuePC);
        // TODO Add option to store DBRef here instead of just the id string
        dbObject.put(fieldName, IdentityUtils.getPersistableIdentityForId(valueId)); // Store the id String form
    }

    protected void processContainerRelationField(AbstractMemberMetaData mmd, Object value, ExecutionContext ec, String fieldName)
    {
        // Collection/Map/Array
        if (mmd.hasCollection())
        {
            Collection coll = (Collection)value;
            if ((insert && !mmd.isCascadePersist()) || (!insert && !mmd.isCascadeUpdate()))
            {
                // Field doesnt support cascade-persist so no reachability
                if (NucleusLogger.PERSISTENCE.isDebugEnabled())
                {
                    NucleusLogger.PERSISTENCE.debug(Localiser.msg("007006", mmd.getFullFieldName()));
                }

                // Check for any persistable elements that aren't persistent
                for (Object element : coll)
                {
                    if (!ec.getApiAdapter().isDetached(element) && !ec.getApiAdapter().isPersistent(element))
                    {
                        // Element is not persistent so throw exception
                        throw new ReachableObjectNotCascadedException(mmd.getFullFieldName(), element);
                    }
                }
            }

            Collection collIds = new ArrayList();
            Iterator collIter = coll.iterator();
            while (collIter.hasNext())
            {
                if (mmd.getCollection().isSerializedElement())
                {
                    // TODO Support Serialised elements
                    throw new NucleusUserException("Don't currently support serialised collection elements at " +
                        mmd.getFullFieldName() + " . Serialise the whole field");
                }

                Object element = collIter.next();
                if (element != null)
                {
                    Object elementPC = ec.persistObjectInternal(element, null, -1, -1);
                    Object elementID = ec.getApiAdapter().getIdForObject(elementPC);
                    // TODO Add option to store DBRef here instead of just the id string
                    collIds.add(IdentityUtils.getPersistableIdentityForId(elementID));
                }
                else
                {
                    collIds.add("NULL");
                }
            }
            dbObject.put(fieldName, collIds); // Store List<String> of ids
        }
        else if (mmd.hasMap())
        {
            Collection<DBObject> collEntries = new HashSet();
            Map map = (Map)value;
            Iterator<Map.Entry> mapIter = map.entrySet().iterator();
            while (mapIter.hasNext())
            {
                if (mmd.getMap().isSerializedKey() || mmd.getMap().isSerializedValue())
                {
                    // TODO Support Serialised elements
                    throw new NucleusUserException("Don't currently support serialised map keys/values at " +
                        mmd.getFullFieldName() + " . Serialise the whole field");
                }

                Map.Entry entry = mapIter.next();
                Object mapKey = entry.getKey();
                Object mapValue = entry.getValue();

                BasicDBObject entryObj = new BasicDBObject();
                if (ec.getApiAdapter().isPersistable(mapKey))
                {
                    Object pc = ec.persistObjectInternal(mapKey, null, -1, -1);
                    Object keyID = ec.getApiAdapter().getIdForObject(pc);
                    // TODO Add option to store DBRef here instead of just the id string
                    entryObj.append("key", IdentityUtils.getPersistableIdentityForId(keyID));
                }
                else
                {
                    processContainerNonRelationField("key", ec, mapKey, entryObj, mmd, FieldRole.ROLE_MAP_KEY);
                }

                if (ec.getApiAdapter().isPersistable(mapValue))
                {
                    Object pc = ec.persistObjectInternal(mapValue, null, -1, -1);
                    Object valueID = ec.getApiAdapter().getIdForObject(pc);
                    // TODO Add option to store DBRef here instead of just the id string
                    entryObj.append("value", IdentityUtils.getPersistableIdentityForId(valueID));
                }
                else
                {
                    processContainerNonRelationField("value", ec, mapValue, entryObj, mmd, FieldRole.ROLE_MAP_VALUE);
                }

                collEntries.add(entryObj);
            }
            dbObject.put(fieldName, collEntries); // Store Collection<DBObject> of entries
        }
        else if (mmd.hasArray())
        {
            Collection<String> collIds = new ArrayList();
            for (int i=0;i<Array.getLength(value);i++)
            {
                if (mmd.getArray().isSerializedElement())
                {
                    // TODO Support Serialised elements
                    throw new NucleusUserException("Don't currently support serialised array elements at " + 
                        mmd.getFullFieldName() + " . Serialise the whole field");
                }

                Object element = Array.get(value, i);
                if (element != null)
                {
                    Object elementPC = ec.persistObjectInternal(element, null, -1, -1);
                    Object elementID = ec.getApiAdapter().getIdForObject(elementPC);
                    // TODO Add option to store DBRef here instead of just the id string
                    collIds.add(IdentityUtils.getPersistableIdentityForId(elementID));
                }
                else
                {
                    collIds.add("NULL");
                }
            }
            dbObject.put(fieldName, collIds); // Store List<String> of ids
        }
    }

    protected void processContainerNonRelationField(String fieldName, ExecutionContext ec, Object value, 
            DBObject dbObject, AbstractMemberMetaData mmd, FieldRole fieldRole)
    {
        Object storeValue = MongoDBUtils.getStoredValueForField(ec, mmd, value, fieldRole);
        dbObject.put(fieldName, storeValue);
    }
}
