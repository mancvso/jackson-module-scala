package com.fasterxml.jackson.module.scala.ser

import collection.JavaConverters._
import com.fasterxml.jackson.module.scala.JacksonModule
import org.codehaus.jackson.JsonGenerator
import org.codehaus.jackson.`type`.JavaType
import org.codehaus.jackson.map.{BeanDescription, SerializationConfig, Serializers, SerializerProvider, JsonSerializer, BeanProperty, TypeSerializer}
import org.codehaus.jackson.map.`type`.CollectionLikeType
import org.codehaus.jackson.map.ser.std.{CollectionSerializer, AsArraySerializerBase}
import com.fasterxml.jackson.module.scala.modifiers.{IterableTypeModifierModule}

private class IterableSerializer(seqType: Class[_], elemType: JavaType, staticTyping: Boolean, vts: Option[TypeSerializer], property: BeanProperty, valueSerializer: Option[JsonSerializer[AnyRef]])
  extends AsArraySerializerBase[collection.Iterable[Any]](seqType, elemType, staticTyping, vts.orNull, property, valueSerializer.orNull) {

  val collectionSerializer =
    new CollectionSerializer(elemType, staticTyping, vts.orNull, property, valueSerializer.orNull)

  def serializeContents(value: Iterable[Any], jgen: JsonGenerator, provider: SerializerProvider)
  {
    collectionSerializer.serializeContents(value.asJavaCollection, jgen, provider)
  }

  override def _withValueTypeSerializer(newVts: TypeSerializer) =
    new IterableSerializer(seqType, elemType, staticTyping, Option(newVts), property, valueSerializer)
}

private object IterableSerializerResolver extends Serializers.Base {

  override def findCollectionLikeSerializer(config: SerializationConfig,
                   collectionType: CollectionLikeType,
                   beanDescription: BeanDescription,
                   beanProperty: BeanProperty,
                   elementTypeSerializer: TypeSerializer,
                   elementSerializer: JsonSerializer[Object]): JsonSerializer[_] = {
    val rawClass = collectionType.getRawClass
    if (!classOf[collection.Iterable[Any]].isAssignableFrom(rawClass)) null else
    if (classOf[collection.Map[Any,Any]].isAssignableFrom(rawClass)) null else
    new IterableSerializer(rawClass, collectionType.containedType(0), false, Option(elementTypeSerializer), beanProperty,
      Option(elementSerializer))
  }

}

trait IterableSerializerModule extends IterableTypeModifierModule {
  self: JacksonModule =>

  this += IterableSerializerResolver
}