package com.googlecode.objectify.impl.translate;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.googlecode.objectify.ObjectifyFactory;
import com.googlecode.objectify.annotation.Embed;
import com.googlecode.objectify.annotation.Index;
import com.googlecode.objectify.annotation.Unindex;
import com.googlecode.objectify.impl.Path;
import com.googlecode.objectify.impl.Property;
import com.googlecode.objectify.impl.TypeUtils;
import com.googlecode.objectify.impl.node.EntityNode;
import com.googlecode.objectify.impl.node.ListNode;
import com.googlecode.objectify.impl.node.MapNode;
import com.googlecode.objectify.impl.translate.CollectionTranslatorFactory.CollectionListNodeTranslator;
import com.googlecode.objectify.impl.translate.MapTranslatorFactory.MapMapNodeTranslator;
import com.googlecode.objectify.repackaged.gentyref.GenericTypeReflector;


/**
 * <p>Translator which can map whole embedded classes.  Note that root classes are just a special case of an embedded
 * class, so there is a method here to create a root translator.</p>
 * 
 * @author Jeff Schnitzer <jeff@infohazard.org>
 */
public class EmbedTranslatorFactory<T> implements TranslatorFactory<T>
{
	/** Exists only so that we can get an array with the @Embed annotation */
	@Embed
	private static class HasEmbed {}
	
	/** Associates a Property with a Translator */
	private static class EachProperty {
		Property property;
		Translator<Object> translator;
		
		@SuppressWarnings("unchecked")
		public EachProperty(Property prop, Translator<?> trans) {
			this.property = prop;
			this.translator = (Translator<Object>)trans;
		}
		
		/** Executes loading this value from the node and setting it on the field */
		@SuppressWarnings({ "rawtypes", "unchecked" })
		public void executeLoad(MapNode node, Object onPojo, LoadContext ctx) {
			EntityNode actual = getChild(node, property.getAllNames());
			
			// We only execute if there is a real node.  Note that even a null value in the data will have a real
			// MapNode with a propertyValue of null, so this is a legitimate test for data in the source Entity
			if (actual != null) {
				try {
					// We have a couple special cases - for collection/map fields we would like to preserve the original
					// instance, if one exists.  It might have been initialized with custom comparators, etc.
					Object value;
					if (translator instanceof CollectionListNodeTranslator && actual instanceof ListNode) {
						Collection coll = (Collection)this.property.get(onPojo);
						value = ((CollectionListNodeTranslator)translator).loadListIntoExistingCollection((ListNode)actual, ctx, coll);
					}
					else if (translator instanceof MapMapNodeTranslator && actual instanceof MapNode) {
						Map map = (Map)this.property.get(onPojo);
						value = ((MapMapNodeTranslator)translator).loadMapIntoExistingMap((MapNode)actual, ctx, map);
					}
					else {
						value = translator.load(actual, ctx);
					}
					
					property.set(onPojo, value);
				}
				catch (SkipException ex) {
					// No prob, skip this one
				}
			}
		}
		
		/** 
		 * Executes saving the field value from the pojo into the mapnode
		 * @param onPojo is the parent pojo which holds the property we represent
		 * @param node is the node that corresponds to the parent pojo; we create a new node and put it in here
		 * @param index is the default state of indexing up to this point 
		 */
		public void executeSave(Object onPojo, MapNode node, boolean index, SaveContext ctx) {
			if (property.isSaved(onPojo)) {
				// Look for an override on indexing
				Boolean propertyIndexInstruction = property.getIndexInstruction(onPojo);
				if (propertyIndexInstruction != null)
					index = propertyIndexInstruction;
				
				Object value = property.get(onPojo);
				try {
					EntityNode child = translator.save(value, index, ctx);
					node.put(property.getName(), child);
				}
				catch (SkipException ex) {
					// No problem, do nothing
				}
			}
		}
		
		/**
		 * @param parent is the collection in which to look
		 * @param names is a list of names to look for in the parent 
		 * @return one child which has a name in the parent
		 * @throws IllegalStateException if there are multiple name matches
		 */
		private EntityNode getChild(MapNode parent, String[] names) {
			EntityNode child = null;
			
			for (String name: names) {
				EntityNode child2 = parent.get(name);
				
				if (child != null && child2 != null)
					throw new IllegalStateException("Collision trying to load field; multiple name matches at " + parent.getPath());
				
				child = child2;
			}
			
			return child;
		}
	}
	
	/**
	 * Create a class loader for a root entity by making it work just like an embedded class.  Fakes
	 * the @Embed annotation as a fieldAnnotation. 
	 */
	public Translator<T> createRoot(Type type, CreateContext ctx) {
		return create(Path.root(), HasEmbed.class.getAnnotations(), type, ctx);
	}

	/* (non-Javadoc)
	 * @see com.googlecode.objectify.impl.load.LoaderFactory#create(com.googlecode.objectify.ObjectifyFactory, java.lang.reflect.Type, java.lang.annotation.Annotation[])
	 */
	@Override
	public Translator<T> create(final Path path, final Annotation[] fieldAnnotations, final Type type, CreateContext ctx)
	{
		@SuppressWarnings("unchecked")
		final Class<T> clazz = (Class<T>)GenericTypeReflector.erase(type);
		
		// We only work with @Embed classes
		if (TypeUtils.getAnnotation(Embed.class, fieldAnnotations) == null && clazz.getAnnotation(Embed.class) == null)
			return null;
		
		ctx.setInEmbed(true);
		try {
			final ObjectifyFactory fact = ctx.getFactory();
			
			final List<EachProperty> fieldLoaders = new ArrayList<EachProperty>();
			
			for (Property prop: TypeUtils.getProperties(clazz)) {
				Path propPath = path.extend(prop.getName());
				Translator<?> loader = fact.getTranslators().create(propPath, prop.getAnnotations(), prop.getType());
				fieldLoaders.add(new EachProperty(prop, loader));
				
				// Sanity check here
				if (prop.hasIgnoreSaveConditions() && ctx.isInCollection() && ctx.isInEmbed())	// of course we're in embed
					propPath.throwIllegalState("You cannot use conditional @IgnoreSave within @Embed collections. @IgnoreSave is only allowed without conditions.");
			}
			
			// If there is an index/unindex instruction on the class, it should override any default
			Index ind = clazz.getAnnotation(Index.class);
			Unindex unind = clazz.getAnnotation(Unindex.class);
			if (ind != null && unind != null)
				throw new IllegalStateException("You cannot have @Index and @Unindex on the same class");
			
			final Boolean classIndexInstruction = (ind != null) ? Boolean.TRUE : ((unind != null) ? Boolean.FALSE : null);
			
			return new MapNodeTranslator<T>(path) {
				@Override
				protected T loadMap(MapNode node, LoadContext ctx) {
					T pojo = fact.construct(clazz);
					
					for (EachProperty fieldLoader: fieldLoaders)
						fieldLoader.executeLoad(node, pojo, ctx);
					
					return pojo;
				}
	
				@Override
				protected MapNode saveMap(T pojo, boolean index, SaveContext ctx) {
					MapNode node = new MapNode(path);
					
					if (classIndexInstruction != null)
						index = classIndexInstruction;
					
					for (EachProperty fieldLoader: fieldLoaders)
						fieldLoader.executeSave(pojo, node, index, ctx);
					
					return node;
				}
			};
		}
		finally {
			ctx.setInEmbed(false);
		}
	}
}
