package com.fasterxml.jackson
package module.scala
package ser

import util.Implicits._
import modifiers.OptionTypeModifierModule

import core.JsonGenerator
import databind._
import jsontype.TypeSerializer
import jsonschema.{JsonSchema, SchemaAware}
import ser.{ContextualSerializer, BeanPropertyWriter, BeanSerializerModifier, Serializers}
import ser.impl.UnknownSerializer
import ser.std.StdSerializer
import com.fasterxml.jackson.databind.`type`.CollectionLikeType
import jsonFormatVisitors.JsonFormatVisitorWrapper

import java.lang.reflect.Type
import java.{util => ju}

private class OptionSerializer(elementType: Option[JavaType],
                               valueTypeSerializer: Option[TypeSerializer],
                               beanProperty: Option[BeanProperty],
                               elementSerializer: Option[JsonSerializer[AnyRef]])
  extends StdSerializer[Option[AnyRef]](classOf[Option[AnyRef]])
  with ContextualSerializer
  with SchemaAware
{
  override def serialize(value: Option[AnyRef], jgen: JsonGenerator, provider: SerializerProvider) {
    def doSerialize(v: AnyRef) = {
      val cls = v.getClass
      val ser = elementType.filter(_.hasGenericTypes) match {
        case Some(et) => provider.findValueSerializer(provider.constructSpecializedType(et, cls), beanProperty.orNull)
        case None => provider.findValueSerializer(cls, beanProperty.orNull)
      }
      ser.serialize(v, jgen, provider)
    }

    valueTypeSerializer match {
      case Some(vt) => serializeWithType(value, jgen, provider, vt)
      case None =>
        (value, elementSerializer) match {
          case (Some(v), Some(vs: UnknownSerializer)) => doSerialize(v)
          case (Some(v), None) => doSerialize(v)
          case (Some(v), Some(vs)) => vs.serialize(v, jgen, provider)
          case (None, _) => provider.defaultSerializeNull(jgen)
        }
    }
  }

  override def serializeWithType(value: Option[AnyRef], jgen: JsonGenerator, provider: SerializerProvider, typeSer: TypeSerializer) {
    def doSerialize(v: AnyRef) = {
      val cls = v.getClass
      val ser = elementType.filter(_.hasGenericTypes) match {
        case Some(et) => provider.findTypedValueSerializer(provider.constructSpecializedType(et, cls), true, beanProperty.orNull)
        case None => provider.findTypedValueSerializer(cls, true, beanProperty.orNull)
      }
      ser.serializeWithType(v, jgen, provider, typeSer)
    }

    (value, elementSerializer) match {
      case (Some(v), Some(vs: UnknownSerializer)) => doSerialize(v)
      case (Some(v), None) => doSerialize(v)
      case (Some(v), Some(vs)) => vs.serializeWithType(v, jgen, provider, typeSer)
      case (None, _) => provider.defaultSerializeNull(jgen)
    }
  }

  override def createContextual(prov: SerializerProvider, property: BeanProperty): JsonSerializer[_] = {
    // Based on the version in AsArraySerializerBase
    val typeSer = valueTypeSerializer.optMap(_.forProperty(property))
    var ser: Option[JsonSerializer[_]] =
      Option(property).flatMap { p =>
        Option(p.getMember).flatMap { m =>
          Option(prov.getAnnotationIntrospector.findContentSerializer(m)).map { serDef =>
            prov.serializerInstance(m, serDef)
          }
        }
      } orElse elementSerializer
    ser = Option(findConvertingContentSerializer(prov, property, ser.orNull))
    if (ser.isEmpty) {
      if (elementType.isDefined) {
        if (hasContentTypeAnnotation(prov, property)) {
          ser = Option(prov.findValueSerializer(elementType.get, property))
        }
      }
    }
    else {
      ser = Option(prov.handleSecondaryContextualization(ser.get, property))
    }
    if ((ser != elementSerializer) || (property != beanProperty.orNull) || (valueTypeSerializer != typeSer))
      new OptionSerializer(elementType, typeSer, Option(property), ser.asInstanceOf[Option[JsonSerializer[AnyRef]]])
    else this
  }

  def hasContentTypeAnnotation(provider: SerializerProvider, property: BeanProperty) = {
    Option(property).exists { p =>
      Option(provider.getAnnotationIntrospector).exists { intr =>
        Option(intr.refineSerializationType(provider.getConfig, p.getMember, p.getType)).isDefined
      }
    }
  }

  override def isEmpty(value: Option[AnyRef]): Boolean = value.isEmpty

  override def getSchema(provider: SerializerProvider, typeHint: Type): JsonNode =
    getSchema(provider, typeHint, isOptional = true)

  override def getSchema(provider: SerializerProvider, typeHint: Type, isOptional: Boolean): JsonNode = {
    val contentSerializer = elementSerializer.getOrElse {
      val javaType = provider.constructType(typeHint)
      val componentType = javaType.getContentType
      provider.findTypedValueSerializer(componentType, true, beanProperty.orNull)
    }
    contentSerializer match {
      case cs: SchemaAware => cs.getSchema(provider, contentSerializer.handledType(), isOptional)
      case _ => JsonSchema.getDefaultSchemaNode
    }
  }

  override def acceptJsonFormatVisitor(wrapper: JsonFormatVisitorWrapper, javaType: JavaType) {
    val containedType = javaType.getContentType
    val ser = elementSerializer.getOrElse(wrapper.getProvider.findTypedValueSerializer(containedType, true, beanProperty.orNull))
    ser.acceptJsonFormatVisitor(wrapper, containedType)
  }
}

private class OptionPropertyWriter(delegate: BeanPropertyWriter) extends BeanPropertyWriter(delegate)
{
  override def serializeAsField(bean: AnyRef, jgen: JsonGenerator, prov: SerializerProvider) {
    (get(bean), _nullSerializer) match {
      // value is None, which we'll serialize as null, but there's no
      // null-serializer, which means it should be suppressed
      case (None, null) => return
      case _ => super.serializeAsField(bean, jgen, prov)
    }
  }
}

private object OptionBeanSerializerModifier extends BeanSerializerModifier {

  override def changeProperties(config: SerializationConfig,
                                beanDesc: BeanDescription,
                                beanProperties: ju.List[BeanPropertyWriter]): ju.List[BeanPropertyWriter] = {
    import scala.collection.JavaConversions._
    beanProperties.transform(w => if (isOption(w)) new OptionPropertyWriter(w) else w)
  }

  private[this] def isOption(writer: BeanPropertyWriter): Boolean = {
    classOf[Option[_]].isAssignableFrom(writer.getPropertyType)
  }
}

private object OptionSerializerResolver extends Serializers.Base {

  private val OPTION = classOf[Option[_]]

  override def findCollectionLikeSerializer(config: SerializationConfig,
                                            `type`: CollectionLikeType,
                                            beanDesc: BeanDescription,
                                            elementTypeSerializer: TypeSerializer ,
                                            elementValueSerializer: JsonSerializer[AnyRef]
                                           ): JsonSerializer[_] =

    if (!OPTION.isAssignableFrom(`type`.getRawClass)) null
    else {
      val elementType = `type`.getContentType
      val typeSer = Option(elementTypeSerializer).orElse(Option(elementType.getTypeHandler.asInstanceOf[TypeSerializer]))
      val valSer = Option(elementValueSerializer).orElse(Option(elementType.getValueHandler.asInstanceOf[JsonSerializer[AnyRef]]))
      new OptionSerializer(Option(`type`.getContentType), typeSer, None, valSer)
    }
}

trait OptionSerializerModule extends OptionTypeModifierModule {
  this += { ctx =>
    ctx addSerializers OptionSerializerResolver
    ctx addBeanSerializerModifier OptionBeanSerializerModifier
  }
}
