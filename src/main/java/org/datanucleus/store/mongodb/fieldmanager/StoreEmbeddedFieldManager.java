/**********************************************************************
Copyright (c) 2011 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
   ...
**********************************************************************/
package org.datanucleus.store.mongodb.fieldmanager;

import java.util.ArrayList;
import java.util.List;

import org.datanucleus.ClassLoaderResolver;
import org.datanucleus.ExecutionContext;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.EmbeddedMetaData;
import org.datanucleus.metadata.MetaDataUtils;
import org.datanucleus.metadata.RelationType;
import org.datanucleus.state.ObjectProvider;
import org.datanucleus.store.fieldmanager.FieldManager;
import org.datanucleus.store.mongodb.MongoDBUtils;
import org.datanucleus.store.schema.table.MemberColumnMapping;
import org.datanucleus.store.schema.table.Table;

import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;

/**
 * FieldManager for the persistence of a related embedded object (1-1/N-1 relation).
 * This handles flat embedding of related embedded objects, where the field of the embedded object become a field in the owner document.
 */
public class StoreEmbeddedFieldManager extends StoreFieldManager
{
    /** Metadata for the embedded member (maybe nested) that this FieldManager represents). */
    protected List<AbstractMemberMetaData> mmds;

    public StoreEmbeddedFieldManager(ObjectProvider op, DBObject dbObject, boolean insert, List<AbstractMemberMetaData> mmds, Table table)
    {
        super(op, dbObject, insert, table);
        this.mmds = mmds;
    }

    protected MemberColumnMapping getColumnMapping(int fieldNumber)
    {
        List<AbstractMemberMetaData> embMmds = new ArrayList<AbstractMemberMetaData>(mmds);
        embMmds.add(cmd.getMetaDataForManagedMemberAtAbsolutePosition(fieldNumber));
        return table.getMemberColumnMappingForEmbeddedMember(embMmds);
    }

    @Override
    public void storeObjectField(int fieldNumber, Object value)
    {
        AbstractMemberMetaData mmd = cmd.getMetaDataForManagedMemberAtAbsolutePosition(fieldNumber);
        AbstractMemberMetaData lastMmd = mmds.get(mmds.size()-1);

        EmbeddedMetaData embmd = mmds.get(0).getEmbeddedMetaData();
        if (mmds.size() == 1 && embmd != null && embmd.getOwnerMember() != null && embmd.getOwnerMember().equals(mmd.getName()))
        {
            // Special case of this member being a link back to the owner. TODO Repeat this for nested and their owners
            if (op != null)
            {
                ObjectProvider[] ownerOPs = ec.getOwnersForEmbeddedObjectProvider(op);
                if (ownerOPs != null && ownerOPs.length == 1 && value != ownerOPs[0].getObject())
                {
                    // Make sure the owner field is set
                    op.replaceField(fieldNumber, ownerOPs[0].getObject());
                }
            }
            return;
        }

        ExecutionContext ec = op.getExecutionContext();
        ClassLoaderResolver clr = ec.getClassLoaderResolver();
        RelationType relationType = mmd.getRelationType(clr);
        if (relationType != RelationType.NONE && MetaDataUtils.getInstance().isMemberEmbedded(ec.getMetaDataManager(), clr, mmd, relationType, lastMmd))
        {
            // Embedded field
            if (RelationType.isRelationSingleValued(relationType))
            {
                // Embedded PC object - This performs "flat embedding" as fields in the same document
                AbstractClassMetaData embCmd = ec.getMetaDataManager().getMetaDataForClass(mmd.getType(), clr);
                if (embCmd == null)
                {
                    throw new NucleusUserException("Field " + mmd.getFullFieldName() +
                        " specified as embedded but metadata not found for the class of type " + mmd.getTypeName());
                }

                // Embedded PC object - can be stored nested in the BSON doc (default), or flat
                boolean nested = MongoDBUtils.isMemberNested(mmd);

                if (RelationType.isBidirectional(relationType))
                {
                    // TODO Add logic for bidirectional relations so we know when to stop embedding
                }

                if (value == null)
                {
                    if (nested)
                    {
                        MemberColumnMapping mapping = getColumnMapping(fieldNumber);
                        for (int i=0;i<mapping.getNumberOfColumns();i++)
                        {
                            dbObject.removeField(mapping.getColumn(i).getName());
                        }
                        return;
                    }

                    // TODO Delete any fields for the embedded object (see Cassandra for example)
                    return;
                }

                List<AbstractMemberMetaData> embMmds = new ArrayList<AbstractMemberMetaData>(mmds);
                embMmds.add(mmd);

                if (nested)
                {
                    // Store sub-embedded object in own DBObject, nested
                    DBObject embeddedObject = new BasicDBObject();

                    // Process all fields of the embedded object
                    ObjectProvider embOP = ec.findObjectProviderForEmbedded(value, op, mmd);
                    FieldManager ffm = new StoreEmbeddedFieldManager(embOP, embeddedObject, insert, embMmds, table);
                    embOP.provideFields(embCmd.getAllMemberPositions(), ffm);

                    MemberColumnMapping mapping = getColumnMapping(fieldNumber);
                    dbObject.put(mapping.getColumn(0).getName(), embeddedObject);
                    return;
                }

                // Process all fields of the embedded object
                ObjectProvider embOP = ec.findObjectProviderForEmbedded(value, op, mmd);
                FieldManager ffm = new StoreEmbeddedFieldManager(embOP, dbObject, insert, embMmds, table);
                embOP.provideFields(embCmd.getAllMemberPositions(), ffm);
                return;
            }
        }

        storeNonEmbeddedObjectField(mmd, relationType, clr, value);
    }
}