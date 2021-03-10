/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.incremental

import org.jetbrains.kotlin.build.report.BuildReporter
import org.jetbrains.kotlin.metadata.deserialization.NameResolverImpl
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmNameResolver
import org.jetbrains.kotlin.metadata.jvm.deserialization.JvmProtoBufUtil
import org.jetbrains.kotlin.metadata.jvm.serialization.JvmStringTable
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.protobuf.MessageLite
import java.io.*

interface JarSnapshot {
    val protos: MutableMap<FqName, ProtoData>
}



class JarSnapshotImpl(override val protos: MutableMap<FqName, ProtoData>) : JarSnapshot {

    companion object {
        fun ObjectInputStream.readStringArray(): Array<String> {
            val size = readInt()
            val stringArray = arrayOfNulls<String>(size)
            repeat(size) {
                stringArray[it] = readUTF()
            }

            return stringArray.requireNoNulls()
        }


        fun ObjectInputStream.readJarSnapshot(): JarSnapshotImpl {
            // Format:
            // numRecords: Int
            // record {
            //   fqName
            //   isClassProtoData
            //   *for packageClassData - packageFqName
            //   protodata via JvmProtoBufUtil
            //   string array with size
            // }
            val size = readInt()
            val mutableMap = hashMapOf<FqName, ProtoData>()
            repeat(size) {
                val fqNameString = readUTF()
                val isClassProtoData = readBoolean()
                if (isClassProtoData) {
                    val fqName = FqName(fqNameString)
                    val bytes = readStringArray()
                    val strings = readStringArray()
                    val (nameResolver, classProto) = JvmProtoBufUtil.readClassDataFrom(bytes, strings)
                    mutableMap[fqName] = ClassProtoData(classProto, nameResolver)
                } else {
                    val fqName = FqName(fqNameString)
                    val packageFqName = FqName(readUTF())
                    val bytes = readStringArray()
                    val strings = readStringArray()
                    val (nameResolver, proto) = JvmProtoBufUtil.readPackageDataFrom(bytes, strings)
                    mutableMap[fqName] = PackagePartProtoData(proto, nameResolver, packageFqName)
                }
            }
            return JarSnapshotImpl(mutableMap)
        }

        fun ObjectOutputStream.writeStringArray(stringArray: Array<String>) {
            writeInt(stringArray.size)
            stringArray.forEach { writeUTF(it) }
        }

        fun ObjectOutputStream.writeJarSnapshot(jarSnapshot: JarSnapshot) {
            //TODO temp solution while packageProto is not fully support
            writeInt(jarSnapshot.protos.size)
            for (entry in jarSnapshot.protos) {
                writeUTF(entry.key.asString())
                val protoData = entry.value
                when (protoData) {
                    is ClassProtoData -> {
                        writeBoolean(true) //TODO until PackageProto doesn't work
                        val nameResolver = protoData.nameResolver
                        when (nameResolver) {
                            is NameResolverImpl -> {
                                writeMessageWithNameResolverImpl(protoData.proto, nameResolver)
                            }
                            is JvmNameResolver -> {
                                writeMessageWithJvmNameResolver(protoData.proto, nameResolver)
                            }
                            else -> throw IllegalStateException("Can't store name resolver for class proto: ${nameResolver.javaClass}")
                        }
                    }
                    is PackagePartProtoData -> {
                        writeBoolean(false)
                        writeUTF(protoData.packageFqName.asString())

                        val nameResolver = protoData.nameResolver
                        when (nameResolver) {
                            is JvmNameResolver -> {
                                writeMessageWithJvmNameResolver(protoData.proto, nameResolver)
                            }
                            is NameResolverImpl -> {
                                writeMessageWithNameResolverImpl(protoData.proto, nameResolver)
                            }
                            else -> throw IllegalStateException("Can't store name resolver for package proto: ${nameResolver.javaClass}")
                        }

                    }
                }
            }
        }

        private fun ObjectOutputStream.writeMessageWithNameResolverImpl(
            message: MessageLite,
            nameResolver: NameResolverImpl
        ) {
            val stringTable = JvmStringTable()
            repeat(nameResolver.strings.getStringCount()) {
                stringTable.getStringIndex(nameResolver.getString(it))
            }
            repeat(nameResolver.qualifiedNames.qualifiedNameCount) {
                stringTable.getQualifiedClassNameIndex(
                    nameResolver.getQualifiedClassName(it),
                    nameResolver.isLocalClassName(it)
                )
            }
            val writeData = JvmProtoBufUtil.writeData(message, stringTable)
            writeStringArray(writeData)
            val size = nameResolver.strings.getStringCount()
            writeInt(size)
            repeat(size) {
                val string = nameResolver.getString(it)
                writeUTF(string)
            }
        }

        private fun ObjectOutputStream.writeMessageWithJvmNameResolver(
            message: MessageLite,
            nameResolver: JvmNameResolver
        ) {
            val writeData = JvmProtoBufUtil.writeData(message, JvmStringTable(nameResolver))
            writeStringArray(writeData)
            writeStringArray(nameResolver.strings)
        }

        fun write(buildInfo: JarSnapshot, file: File) {
            ObjectOutputStream(FileOutputStream(file)).use {
                it.writeJarSnapshot(buildInfo)
            }
        }

        fun read(file: File, reporter: BuildReporter): JarSnapshot? {
            if (!file.exists()) {
                reporter.report { "jar snapshot $file is found for jar" }
                return null
            }

            ObjectInputStream(FileInputStream(file)).use {
                return it.readJarSnapshot()
            }
        }
    }
}