/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package com.sun.tools.xjc.api.impl.j2s;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.xml.bind.SchemaOutputResolver;
import javax.xml.bind.annotation.XmlList;
import javax.xml.bind.annotation.adapters.XmlJavaTypeAdapter;
import javax.xml.namespace.QName;
import javax.xml.transform.Result;

import com.sun.mirror.declaration.FieldDeclaration;
import com.sun.mirror.declaration.MethodDeclaration;
import com.sun.mirror.declaration.TypeDeclaration;
import com.sun.mirror.type.TypeMirror;
import com.sun.mirror.type.PrimitiveType;
import com.sun.tools.xjc.api.ErrorListener;
import com.sun.tools.xjc.api.J2SJAXBModel;
import com.sun.tools.xjc.api.Reference;
import com.sun.xml.bind.v2.model.annotation.AnnotationReader;
import com.sun.xml.bind.v2.model.core.ArrayInfo;
import com.sun.xml.bind.v2.model.core.ClassInfo;
import com.sun.xml.bind.v2.model.core.Element;
import com.sun.xml.bind.v2.model.core.ElementInfo;
import com.sun.xml.bind.v2.model.core.EnumLeafInfo;
import com.sun.xml.bind.v2.model.core.NonElement;
import com.sun.xml.bind.v2.model.core.Ref;
import com.sun.xml.bind.v2.model.core.TypeInfoSet;
import com.sun.xml.bind.v2.model.nav.Navigator;
import com.sun.xml.bind.v2.schemagen.XmlSchemaGenerator;
import com.sun.xml.txw2.output.ResultFactory;

/**
 * @author Kohsuke Kawaguchi (kk@kohsuke.org)
 */
final class JAXBModelImpl implements J2SJAXBModel {

    private final Map<QName,Reference> additionalElementDecls;

    private final List<String> classList = new ArrayList<String>();

    private final TypeInfoSet<TypeMirror,TypeDeclaration,FieldDeclaration,MethodDeclaration> types;

    private final AnnotationReader<TypeMirror,TypeDeclaration,FieldDeclaration,MethodDeclaration> reader;

    /**
     * Lazily created schema generator.
     */
    private XmlSchemaGenerator<TypeMirror,TypeDeclaration,FieldDeclaration,MethodDeclaration> xsdgen;

    /**
     * Look up table from an externally visible {@link Reference} object
     * to our internal format.
     */
    private final Map<Reference,NonElement<TypeMirror,TypeDeclaration>> refMap =
        new HashMap<Reference, NonElement<TypeMirror,TypeDeclaration>>();

    public JAXBModelImpl(TypeInfoSet<TypeMirror, TypeDeclaration, FieldDeclaration, MethodDeclaration> types,
                         AnnotationReader<TypeMirror, TypeDeclaration, FieldDeclaration, MethodDeclaration> reader,
                         Collection<Reference> rootClasses,
                         Map<QName, Reference> additionalElementDecls) {
        this.types = types;
        this.reader = reader;
        this.additionalElementDecls = additionalElementDecls;

        Navigator<TypeMirror,TypeDeclaration,FieldDeclaration,MethodDeclaration> navigator = types.getNavigator();

        for( ClassInfo<TypeMirror,TypeDeclaration> i : types.beans().values() ) {
            classList.add(i.getName());
        }

        for(ArrayInfo<TypeMirror,TypeDeclaration> a : types.arrays().values()) {
            String javaName = navigator.getTypeName(a.getType());
            classList.add(javaName);
        }

        for( EnumLeafInfo<TypeMirror,TypeDeclaration> l : types.enums().values() ) {
            QName tn = l.getTypeName();
            if(tn!=null) {
                String javaName = navigator.getTypeName(l.getType());
                classList.add(javaName);
            }
        }

        for (Reference ref : rootClasses)
            refMap.put(ref,getXmlType(ref));

        // check for collision between "additional" ones and the ones given to JAXB
        // and eliminate duplication
        Iterator<Map.Entry<QName, Reference>> itr = additionalElementDecls.entrySet().iterator();
        while(itr.hasNext()) {
            Map.Entry<QName, Reference> entry = itr.next();
            if(entry.getValue()==null)      continue;

            NonElement<TypeMirror,TypeDeclaration> xt = getXmlType(entry.getValue());

            assert xt!=null;
            refMap.put(entry.getValue(),xt);
            if(xt instanceof ClassInfo) {
                ClassInfo<TypeMirror,TypeDeclaration> xct = (ClassInfo<TypeMirror,TypeDeclaration>) xt;
                Element<TypeMirror,TypeDeclaration> elem = xct.asElement();
                if(elem!=null && elem.getElementName().equals(entry.getKey())) {
                    itr.remove();
                    continue;
                }
            }
            ElementInfo<TypeMirror,TypeDeclaration> ei = types.getElementInfo(null,entry.getKey());
            if(ei!=null && ei.getContentType()==xt)
                itr.remove();
        }
    }

    public List<String> getClassList() {
        return classList;
    }

    public QName getXmlTypeName(Reference javaType) {
        NonElement<TypeMirror,TypeDeclaration> ti = refMap.get(javaType);

        if(ti!=null)
            return ti.getTypeName();

        return null;
    }

    private NonElement<TypeMirror,TypeDeclaration> getXmlType(Reference r) {
        if(r==null)
            throw new IllegalArgumentException();

        XmlJavaTypeAdapter xjta = r.annotations.getAnnotation(XmlJavaTypeAdapter.class);
        XmlList xl = r.annotations.getAnnotation(XmlList.class);

        Ref<TypeMirror, TypeDeclaration> ref = new Ref<TypeMirror, TypeDeclaration>(
            reader,types.getNavigator(),r.type,xjta,xl);

        return types.getTypeInfo(ref);
    }

    public void generateSchema(SchemaOutputResolver outputResolver, ErrorListener errorListener) throws IOException {
        getSchemaGenerator().write(outputResolver,errorListener);
    }

    public void generateEpisodeFile(Result output) {
        getSchemaGenerator().writeEpisodeFile(ResultFactory.createSerializer(output));
    }

    private synchronized XmlSchemaGenerator<TypeMirror, TypeDeclaration, FieldDeclaration, MethodDeclaration> getSchemaGenerator() {
        if(xsdgen==null) {
            xsdgen = new XmlSchemaGenerator<TypeMirror,TypeDeclaration,FieldDeclaration,MethodDeclaration>( types.getNavigator(), types );

            for (Map.Entry<QName, Reference> e : additionalElementDecls.entrySet()) {
                Reference value = e.getValue();
                if(value!=null) {
                    NonElement<TypeMirror, TypeDeclaration> typeInfo = refMap.get(value);
                    if(typeInfo==null)
                        throw new IllegalArgumentException(e.getValue()+" was not specified to JavaCompiler.bind");
                    xsdgen.add(e.getKey(),!(value.type instanceof PrimitiveType),typeInfo);
                } else {
                    xsdgen.add(e.getKey(),false,null);
                }
            }
        }
        return xsdgen;
    }
}
