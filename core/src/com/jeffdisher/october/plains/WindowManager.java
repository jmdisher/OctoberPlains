package com.jeffdisher.october.plains;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.aspects.CraftAspect;
import com.jeffdisher.october.aspects.Environment;
import com.jeffdisher.october.aspects.InventoryAspect;
import com.jeffdisher.october.data.BlockProxy;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.Block;
import com.jeffdisher.october.types.Craft;
import com.jeffdisher.october.types.CraftOperation;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.FuelState;
import com.jeffdisher.october.types.Inventory;
import com.jeffdisher.october.types.Item;
import com.jeffdisher.october.types.Items;
import com.jeffdisher.october.types.MutableInventory;
import com.jeffdisher.october.utils.Assert;


/**
 * This class manages the various overlay windows in the system.
 */
public class WindowManager
{
	public static final float NO_PROGRESS = 0.0f;

	private final Environment _environment;
	private final GL20 _gl;
	private final TextureAtlas _atlas;
	private final Function<AbsoluteLocation, BlockProxy> _blockLoader;
	private final TextManager _textManager;

	private int _program;
	private int _uOffset;
	private int _uScale;
	private int _uTexture;
	private int _uTextureBase;
	private int _uTextureScale;

	// We use 2 buffers here, but they only differ in that the tile buffer knows the texture atlas size while the label buffer is 1.0/1.0.
	private int _atlasVertexBuffer;
	private int _unitVertexBuffer;

	private int _backgroundTexture;
	private int _backgroundHighlightTexture;
	private Entity _entity;

	private _WindowMode _mode;
	private AbsoluteLocation _openInventoryLocation;

	public WindowManager(Environment environment, GL20 gl, TextureAtlas atlas, Function<AbsoluteLocation, BlockProxy> blockLoader)
	{
		_environment = environment;
		_gl = gl;
		_atlas = atlas;
		_blockLoader = blockLoader;
		_textManager = new TextManager(gl);
		
		// Create the program we will use for the window overlays.
		// Explanation of these:
		//  We only draw rectangles and they are either a tile or a text label.  This means that our vertex arrays are
		// both 6 vertices with relative locations and vertex coordinates.  However, the tile rendering will use the
		// texture atlas, hence needing that texture size while the text labels will use full-sized textures.  This
		// means that each vertex array will encode the tile size but will need its base passed in as a uniform.
		_program = RenderSupport.fullyLinkedProgram(_gl
				, "#version 100\n"
						+ "attribute vec2 aPosition;\n"
						+ "attribute vec2 aTexture;\n"
						+ "uniform vec2 uOffset;\n"
						+ "uniform vec2 uScale;\n"
						+ "varying vec2 vTexture;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	vTexture = aTexture;\n"
						+ "	gl_Position = vec4((uScale.x * aPosition.x) + uOffset.x, (uScale.y * aPosition.y) + uOffset.y, 0.0, 1.0);\n"
						+ "}\n"
				, "#version 100\n"
						+ "precision mediump float;\n"
						+ "uniform vec2 uScale;\n"
						+ "uniform sampler2D uTexture;\n"
						+ "uniform vec2 uTextureBase;\n"
						+ "uniform vec2 uTextureScale;\n"
						+ "varying vec2 vTexture;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	vec2 texCoord = vec2(clamp((uTextureScale.x * vTexture.x) + uTextureBase.x, 0.0, 1.0), clamp((uTextureScale.y * vTexture.y) + uTextureBase.y, 0.0, 1.0));\n"
						+ "	vec4 tex = texture2D(uTexture, texCoord);\n"
						+ "	vec4 biased = vec4(tex.r, tex.g, tex.b, tex.a);\n"
						+ "	gl_FragColor = vec4(biased.r, biased.g, biased.b, biased.a);\n"
						+ "}\n"
				, new String[] {
						"aPosition",
						"aTexture",
				}
		);
		_uOffset = _gl.glGetUniformLocation(_program, "uOffset");
		_uScale = _gl.glGetUniformLocation(_program, "uScale");
		_uTexture = _gl.glGetUniformLocation(_program, "uTexture");
		_uTextureBase = _gl.glGetUniformLocation(_program, "uTextureBase");
		_uTextureScale = _gl.glGetUniformLocation(_program, "uTextureScale");
		
		// We need to create the vertex buffers for the tile and the label.
		_atlasVertexBuffer = _defineCommonVertices(_gl, _atlas.primaryCoordinateSize);
		_unitVertexBuffer = _defineCommonVertices(_gl, 1.0f);
		
		// Create the background colour texture we will use (just one pixel allowing us to avoid creating a new shader).
		// This is just dark grey with alpha.
		_backgroundTexture = _gl.glGenTexture();
		ByteBuffer textureBufferData = ByteBuffer.allocateDirect(2);
		textureBufferData.order(ByteOrder.nativeOrder());
		textureBufferData.put(new byte[] { 32, (byte)196 });
		((java.nio.Buffer) textureBufferData).flip();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _backgroundTexture);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_LUMINANCE_ALPHA, 1, 1, 0, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		
		// Similarly, we create the background highlight texture for selection.
		// This is just light grey with alpha.
		_backgroundHighlightTexture = _gl.glGenTexture();
		((java.nio.Buffer) textureBufferData).position(0);
		textureBufferData.put(new byte[] { (byte)128, (byte)196 });
		((java.nio.Buffer) textureBufferData).flip();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _backgroundHighlightTexture);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_LUMINANCE_ALPHA, 1, 1, 0, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		
		// By default, we don't have any window mode active.
		_mode = _WindowMode.NONE;
	}

	public EventHandler drawWindowsWithButtonCapture(ClientLogic client, float glX, float glY)
	{
		// Enable our program
		_gl.glUseProgram(_program);
		
		// If there is an item selected, show it.
		Item selectedItem = (null != _entity) ? _entity.selectedItem() : null;
		if (null != selectedItem)
		{
			int count = _entity.inventory().items.get(selectedItem).count();
			_drawItem(selectedItem, count, -0.3f, -0.9f, 0.3f, -0.8f, false, NO_PROGRESS);
		}
		
		// Draw other on-screen meta-data related to the state of the entity.
		if (null != _entity)
		{
			_drawEntityMetaData(_entity);
		}
		
		// Handle the case where we might need to close the inventory window if the block was destroyed or we are too far away.
		if (_mode.usesBlock())
		{
			int absX = Math.abs(_openInventoryLocation.x() - Math.round(_entity.location().x()));
			int absY = Math.abs(_openInventoryLocation.y() - Math.round(_entity.location().y()));
			int absZ = Math.abs(_openInventoryLocation.z() - Math.round(_entity.location().z()));
			boolean isLocationClose = ((absX <= 2) && (absY <= 2) && (absZ <= 2));
			boolean isCorrectType = _mode.isCorrectBlock(_environment, _blockLoader.apply(_openInventoryLocation).getBlock());
			if (!isLocationClose || !isCorrectType)
			{
				_mode = _WindowMode.NONE;
				_openInventoryLocation = null;
			}
		}
		
		// See if we should show the inventory window.
		// (note that the entity could only be null during start-up).
		EventHandler button = null;
		if ((_WindowMode.NONE != _mode) && (null != _entity))
		{
			// Draw the entity inventory.
			Inventory entityInventory = _entity.inventory();
			Inventory blockInventory = (_mode == _WindowMode.FLOOR)
					? _currentBlockInventory()
					: _selectedBlockInventory(_WindowMode.FUEL == _mode)
			;
			
			_MouseOver<Items> overItems = _drawEntityInventory(client, entityInventory, blockInventory, glX, glY);
			if (null != overItems)
			{
				if (null != overItems.context)
				{
					_drawHover(glX, glY, overItems.context.type().name());
				}
				button = overItems.handler;
			}
			
			// Draw the inventory of the ground or selected block.
			if (null != blockInventory)
			{
				String inventoryName = _getInventoryName(_WindowMode.FUEL == _mode);
				_MouseOver<Items> thisButton = _drawBlockInventory(client, blockInventory, inventoryName, glX, glY);
				if (null != thisButton)
				{
					if (null != thisButton.context)
					{
						_drawHover(glX, glY, thisButton.context.type().name());
					}
					button = thisButton.handler;
				}
				
				// If there is an active fuel aspect to this (no matter the display node, draw it).
				if (null != _openInventoryLocation)
				{
					FuelState fuel = _blockLoader.apply(_openInventoryLocation).getFuel();
					if ((null != fuel) && (null != fuel.currentFuel()))
					{
						Item current = fuel.currentFuel();
						float progressBar = (float)fuel.millisFueled() / (float)_environment.fuel.millisOfFuel(current);
						// TODO:  We really need a better solution than these hard-coded positions, everywhere.
						_drawItem(current, 1, -0.45f, - 0.15f, 0.0f, -0.05f, false, progressBar);
					}
				}
			}
			
			// Draw the crafting panel.
			if (_WindowMode.FUEL != _mode)
			{
				_MouseOver<Craft> thisButton = _drawCraftingPanel(client, entityInventory, blockInventory, glX, glY);
				if (null != thisButton)
				{
					if (null != thisButton.context)
					{
						_drawHover(glX, glY, thisButton.context.output.type().name());
					}
					button = thisButton.handler;
				}
			}
		}
		
		// Allow any periodic cleanup.
		_textManager.allowTexturePurge();
		
		return button;
	}

	private void _drawEntityMetaData(Entity thisEntity)
	{
		float labelWidth = 0.1f;
		float labelMargin = 0.80f;
		float valueMargin = labelMargin + labelWidth;
		
		_drawLabel(labelMargin, -0.90f, labelMargin + labelWidth, -0.85f, "Health");
		_drawLabel(valueMargin, -0.90f, valueMargin + labelWidth, -0.85f, Byte.toString(thisEntity.health()));
		
		_drawLabel(labelMargin, -0.95f, labelMargin + labelWidth, -0.90f, "Food");
		_drawLabel(valueMargin, -0.95f, valueMargin + labelWidth, -0.90f, Byte.toString(thisEntity.food()));
		
		_drawLabel(labelMargin, -1.0f, labelMargin + labelWidth, -0.95f, "z-level");
		String zLevel = String.format("%.2f", thisEntity.location().z());
		_drawLabel(valueMargin, -1.0f, valueMargin + labelWidth, -0.95f, zLevel);
	}

	private _MouseOver<Items> _drawEntityInventory(ClientLogic client, Inventory entityInventory, Inventory blockInventory, float glX, float glY)
	{
		_RenderTuple<Items> itemRender = new _RenderTuple<>((float left, float bottom, float right, float top, boolean isMouseOver, Items value) -> {
			Item item = value.type();
			int count = value.count();
			_drawItem(item, count, left, bottom, right, top, isMouseOver, NO_PROGRESS);
			Runnable onClick = null;
			if (isMouseOver)
			{
				onClick = () -> {
					// If this already was selected, clear it.
					if (_entity.selectedItem() == item)
					{
						client.setSelectedItem(null);
					}
					else
					{
						client.setSelectedItem(item);
					}
				};
			}
			return onClick;
		}, 0.55f, 0.05f);
		_RenderTuple<Items> transfer1 = new _RenderTuple<>((float left, float bottom, float right, float top, boolean isMouseOver, Items value) -> {
			_drawBackground(left, bottom, right, top, isMouseOver);
			_drawLabel(left, bottom, right, top, "1");
			Runnable onClick = null;
			if (isMouseOver)
			{
				onClick = () -> {
					AbsoluteLocation location = (null != _openInventoryLocation) ? _openInventoryLocation : GeometryHelpers.getCentreAtFeet(_entity);
					client.pushItemsToTileInventory(location, value.type(), 1, (_WindowMode.FUEL == _mode));
				};
			}
			return onClick;
		}, 0.1f, 0.05f);
		_RenderTuple<Items> transferAll = new _RenderTuple<>((float left, float bottom, float right, float top, boolean isMouseOver, Items value) -> {
			_drawBackground(left, bottom, right, top, isMouseOver);
			_drawLabel(left, bottom, right, top, "All");
			Runnable onClick = null;
			if (isMouseOver)
			{
				onClick = () -> {
					// Find out how many can fit in the block.
					MutableInventory checker = new MutableInventory((null != blockInventory) ? blockInventory : Inventory.start(InventoryAspect.CAPACITY_BLOCK_EMPTY).finish());
					Item item = value.type();
					int max = checker.maxVacancyForItem(item);
					int toDrop = Math.min(value.count(), max);
					if (toDrop > 0)
					{
						AbsoluteLocation location = (null != _openInventoryLocation) ? _openInventoryLocation : GeometryHelpers.getCentreAtFeet(_entity);
						client.pushItemsToTileInventory(location, item, toDrop, (_WindowMode.FUEL == _mode));
					}
				};
			}
			return onClick;
		}, 0.1f, 0.05f);
		return _drawTableWindow("Inventory", 0.05f, 0.05f, 0.95f, 0.95f, glX, glY, 0.1f, 0.05f, entityInventory.items.values(), List.of(itemRender, transfer1, transferAll));
	}

	private _MouseOver<Items> _drawBlockInventory(ClientLogic client, Inventory blockInventory, String inventoryName, float glX, float glY)
	{
		_RenderTuple<Items> itemRender = new _RenderTuple<>((float left, float bottom, float right, float top, boolean isMouseOver, Items value) -> {
			Item item = value.type();
			int count = value.count();
			// We never highlight the label, just the buttons.
			_drawItem(item, count, left, bottom, right, top, false, NO_PROGRESS);
			// We want to return a runnable which does nothing, just so we get the hover effect.
			return isMouseOver
					? () -> {}
					: null
			;
		}, 0.65f, 0.05f);
		_RenderTuple<Items> transfer1 = new _RenderTuple<>((float left, float bottom, float right, float top, boolean isMouseOver, Items value) -> {
			_drawBackground(left, bottom, right, top, isMouseOver);
			_drawLabel(left, bottom, right, top, "1");
			Runnable onClick = null;
			if (isMouseOver)
			{
				onClick = () -> {
					Item item = value.type();
					AbsoluteLocation location = (null != _openInventoryLocation) ? _openInventoryLocation : GeometryHelpers.getCentreAtFeet(_entity);
					client.pullItemsFromTileInventory(location, item, 1, (_WindowMode.FUEL == _mode));
				};
			}
			return onClick;
		}, 0.1f, 0.05f);
		_RenderTuple<Items> transferAll = new _RenderTuple<>((float left, float bottom, float right, float top, boolean isMouseOver, Items value) -> {
			_drawBackground(left, bottom, right, top, isMouseOver);
			_drawLabel(left, bottom, right, top, "All");
			Runnable onClick = null;
			if (isMouseOver)
			{
				onClick = () -> {
					// Find out how many we can hold.
					Item item = value.type();
					int count = value.count();
					MutableInventory checker = new MutableInventory(_entity.inventory());
					int max = checker.maxVacancyForItem(item);
					int toPickUp = Math.min(count, max);
					if (toPickUp > 0)
					{
						AbsoluteLocation location = (null != _openInventoryLocation) ? _openInventoryLocation : GeometryHelpers.getCentreAtFeet(_entity);
						client.pullItemsFromTileInventory(location, item, toPickUp, (_WindowMode.FUEL == _mode));
					}
				};
			}
			return onClick;
		}, 0.1f, 0.05f);
		return _drawTableWindow(inventoryName, -0.95f, -0.95f, 0.95f, -0.05f, glX, glY, 0.1f, 0.05f, blockInventory.items.values(), List.of(itemRender, transfer1, transferAll));
	}

	private _MouseOver<Craft> _drawCraftingPanel(ClientLogic client, Inventory entityInventory, Inventory blockInventory, float glX, float glY)
	{
		// Note that the crafting panel will act a bit differently whether it is the player's inventory or a crafting table.
		CraftOperation crafting;
		Inventory craftingInventory;
		Set<Craft.Classification> classifications;
		boolean canManuallyCraft;
		if (_WindowMode.CRAFTING_TABLE_INVENTORY == _mode)
		{
			// We are looking at the crafting table so grab its crafting aspect.
			crafting = _selectedBlockCrafting();
			craftingInventory = blockInventory;
			classifications = Set.of(Craft.Classification.TRIVIAL, Craft.Classification.COMMON);
			canManuallyCraft = true;
		}
		else if (_WindowMode.FLOOR == _mode)
		{
			// This is the player's inventory so use their operation.
			crafting = (null != _entity) ? _entity.localCraftOperation() : null;
			craftingInventory = entityInventory;
			classifications = Set.of(Craft.Classification.TRIVIAL);
			canManuallyCraft = true;
		}
		else if ((_WindowMode.FURNACE_INVENTORY == _mode) || (_WindowMode.FUEL == _mode))
		{
			// For now, nothing else will show crafting operations.
			crafting = _selectedBlockCrafting();
			craftingInventory = blockInventory;
			// We will render these possible options, just for progress, since we won't let them click.
			classifications = Set.of(Craft.Classification.SPECIAL_FURNACE);
			canManuallyCraft = false;
		}
		else
		{
			// Handle this case once it is created.
			throw Assert.unreachable();
		}
		_RenderTuple<Craft> itemRender = new _RenderTuple<>((float left, float bottom, float right, float top, boolean isMouseOver, Craft craft) -> {
			// We will only check the highlight if this is something we even could craft.
			// Note that this needs to handle 
			boolean canCraft = canManuallyCraft
					? ((null != craftingInventory) ? CraftAspect.canApply(craft, craftingInventory) : false)
					: false
			;
			boolean shouldHighlight = canCraft && isMouseOver;
			// Check to see if this is something we are currently crafting.
			float progressBar = 0.0f;
			if ((null != crafting) && (crafting.selectedCraft() == craft))
			{
				progressBar = (float)crafting.completedMillis() / (float)craft.millisPerCraft;
			}
			_drawItem(craft.output.type(), craft.output.count(), left, bottom, right, top, shouldHighlight, progressBar);
			Runnable onClick = null;
			if (shouldHighlight)
			{
				if (_WindowMode.CRAFTING_TABLE_INVENTORY == _mode)
				{
					// Craft in table.
					onClick = () -> {
						if (CraftAspect.canApply(craft, craftingInventory))
						{
							client.beginCraftInBlock(_openInventoryLocation, craft);
						}
					};
				}
				else
				{
					// Craft in inventory.
					onClick = () -> {
						if (CraftAspect.canApply(craft, craftingInventory))
						{
							client.beginCraft(craft);
						}
					};
				}
			}
			return onClick;
		}, 0.65f, 0.05f);
		return _drawTableWindow("Crafting", -0.95f, 0.05f, -0.05f, 0.95f, glX, glY, 0.1f, 0.05f, _environment.crafting.craftsForClassifications(classifications), List.of(itemRender));
	}

	public void setEntity(Entity entity)
	{
		_entity = entity;
	}

	public void toggleInventory()
	{
		// If it is any window mode, set it to none.  If none, set it to the floor mode.
		_mode = (_WindowMode.NONE == _mode)
				? _WindowMode.FLOOR
				: _WindowMode.NONE
		;
		// The inventory location not used in these modes so clear it.
		_openInventoryLocation = null;
	}

	public void toggleFuelInventory()
	{
		// This only does anything if have the block inventory open.
		if (_WindowMode.FURNACE_INVENTORY == _mode)
		{
			// Furnace has a fuel mode.
			_mode = _WindowMode.FUEL;
		}
		else if (_WindowMode.FUEL == _mode)
		{
			// We can just switch to inventory mode.
			_mode = _WindowMode.FURNACE_INVENTORY;
		}
	}

	public boolean didOpenInventory(AbsoluteLocation blockLocation)
	{
		// See if there is an inventory we can open at the given block location.
		// NOTE:  We don't use this mechanism to talk about air blocks, only actual blocks.
		BlockProxy proxy = _blockLoader.apply(blockLocation);
		// Currently, this is only relevant for crafting table blocks.
		boolean didOpen = false;
		Block block = proxy.getBlock();
		if (_environment.blocks.CRAFTING_TABLE == block)
		{
			// Enter crafting table mode at this block.
			_mode = _WindowMode.CRAFTING_TABLE_INVENTORY;
			// We store the location symbolically, instead of directly storing the inventory, since it can change or be destroyed.
			_openInventoryLocation = blockLocation;
			didOpen = true;
		}
		else if (_environment.blocks.FURNACE == block)
		{
			// Enter furnace mode at this block.
			_mode = _WindowMode.FURNACE_INVENTORY;
			// We store the location symbolically, instead of directly storing the inventory, since it can change or be destroyed.
			_openInventoryLocation = blockLocation;
			didOpen = true;
		}
		return didOpen;
	}

	/**
	 * Used by the caller to determine if the idle operation should be crafting in a block or not so this ONLY returns
	 * non-null if a crafting table is open AND there is an active crafting operation.
	 * 
	 * @return The location of the open block, only if it is a crafting table with an active crafting operation.
	 */
	public AbsoluteLocation getActiveCraftingTable()
	{
		AbsoluteLocation location = null;
		if ((null != _openInventoryLocation)
				&& (_environment.blocks.CRAFTING_TABLE == _blockLoader.apply(_openInventoryLocation).getBlock())
				&& (null != _blockLoader.apply(_openInventoryLocation).getCrafting())
		)
		{
			location = _openInventoryLocation;
		}
		return location;
	}


	private static int _defineCommonVertices(GL20 gl, float textureSize)
	{
		float height = 1.0f;
		float width = 1.0f;
		float textureBaseU = 0.0f;
		float textureBaseV = 0.0f;
		float[] vertices = new float[] {
				0.0f, 0.0f, textureBaseU, textureBaseV + textureSize,
				0.0f, height, textureBaseU, textureBaseV,
				width, height, textureBaseU + textureSize, textureBaseV,
				
				width, height, textureBaseU + textureSize, textureBaseV,
				width, 0.0f, textureBaseU + textureSize, textureBaseV + textureSize,
				0.0f, 0.0f, textureBaseU, textureBaseV + textureSize,
		};
		ByteBuffer direct = ByteBuffer.allocateDirect(vertices.length * Float.BYTES);
		direct.order(ByteOrder.nativeOrder());
		for (float f : vertices)
		{
			direct.putFloat(f);
		}
		((java.nio.Buffer) direct).flip();
		
		int entityBuffer = gl.glGenBuffer();
		gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, entityBuffer);
		gl.glBufferData(GL20.GL_ARRAY_BUFFER, vertices.length * Float.BYTES, direct.asFloatBuffer(), GL20.GL_STATIC_DRAW);
		gl.glEnableVertexAttribArray(0);
		gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		gl.glEnableVertexAttribArray(1);
		gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		return entityBuffer;
	}

	private void _drawItem(Item selectedItem, int count, float left, float bottom, float right, float top, boolean shouldHighlight, float progressBar)
	{
		// We lazily create the label.
		String name = selectedItem.name().toUpperCase();
		
		// Draw the background.
		if (progressBar > 0.0f)
		{
			// There is a progress bar, so draw that, instead.
			float progressRight = left + ((right - left) * progressBar);
			_drawBackground(left, bottom, progressRight, top, true);
		}
		else
		{
			// Just draw the normal background.
			_drawBackground(left, bottom, right, top, shouldHighlight);
		}
		float xScale = (right - left);
		float yScale = (top - bottom);
		
		// Draw the tile.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _atlas.primaryTexture);
		float[] uv = _atlas.baseOfPrimaryTexture(selectedItem);
		float textureBaseU = uv[0];
		float textureBaseV = uv[1];
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, left, bottom);
		// We want to just draw this square.
		_gl.glUniform2f(_uScale, yScale, yScale);
		_gl.glUniform2f(_uTextureBase, textureBaseU, textureBaseV);
		_gl.glUniform2f(_uTextureScale, 1.0f, 1.0f);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _atlasVertexBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
		
		// Draw the number in the corner.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textManager.lazilyLoadStringTexture(Integer.toString(count)));
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, left, bottom);
		_gl.glUniform2f(_uScale, xScale * 0.5f, yScale * 0.5f);
		_gl.glUniform2f(_uTextureBase, 0.0f, 0.0f);
		_gl.glUniform2f(_uTextureScale, 0.5f, 1.0f);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _unitVertexBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
		
		// Draw the label.
		_drawLabel(left + 0.1f, bottom, right, top, name);
	}

	private Inventory _currentBlockInventory()
	{
		AbsoluteLocation block = GeometryHelpers.getCentreAtFeet(_entity);
		BlockProxy proxy = _blockLoader.apply(block);
		return proxy.getInventory();
	}

	private Inventory _selectedBlockInventory(boolean fuelMode)
	{
		BlockProxy proxy = _blockLoader.apply(_openInventoryLocation);
		Inventory inv = null;
		if (fuelMode)
		{
			// Make sure this didn't change under us.
			FuelState fuel = proxy.getFuel();
			if (null != fuel)
			{
				inv = fuel.fuelInventory();
			}
		}
		// If we are not in fuel mode, or the fuel inventory couldn't be created, use the normal one.
		if (null == inv)
		{
			inv = proxy.getInventory();
		}
		return inv;
	}

	private CraftOperation _selectedBlockCrafting()
	{
		BlockProxy proxy = _blockLoader.apply(_openInventoryLocation);
		return proxy.getCrafting();
	}

	private void _drawLabel(float left, float bottom, float right, float top, String label)
	{
		int labelTexture = _textManager.lazilyLoadStringTexture(label);
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, labelTexture);
		float xScale = (right - left);
		float yScale = (top - bottom);
		float xRatio = xScale / yScale;
		// We want to slim down the text so use a multiplier here.
		float textureAspect = (float)TextManager.TEXT_TEXTURE_WIDTH_PIXELS / (float)TextManager.TEXT_TEXTURE_HEIGHT_PIXELS / 2.0f;
		_gl.glUniform2f(_uTextureScale, xRatio / textureAspect, 1.0f);
		_drawUnitRect(left, bottom, right, top);
	}

	private void _drawBackground(float left, float bottom, float right, float top, boolean shouldHighlight)
	{
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, shouldHighlight ? _backgroundHighlightTexture : _backgroundTexture);
		_gl.glUniform2f(_uTextureScale, 1.0f, 1.0f);
		_drawUnitRect(left, bottom, right, top);
	}

	private void _drawUnitRect(float left, float bottom, float right, float top)
	{
		// NOTE:  This assumes that texture unit 0 is already bound to the appropriate texture.
		// The unit vertex buffer has 0.0 - 1.0 on both axes so scale within that.
		float xScale = (right - left);
		float yScale = (top - bottom);
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, left, bottom);
		_gl.glUniform2f(_uScale, xScale, yScale);
		_gl.glUniform2f(_uTextureBase, 0.0f, 0.0f);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _unitVertexBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
	}

	private <T> _MouseOver<T> _drawTableWindow(String title, float leftX, float bottomY, float rightX, float topY, float glX, float glY, float rowHeight, float rowSpace, Collection<T> values, List<_RenderTuple<T>> columns)
	{
		_MouseOver<T> onClick = null;
		// Draw the window outline and create a default catch runnable to block the background.
		_drawBackground(leftX, bottomY, rightX, topY, false);
		if ((leftX <= glX) && (glX <= rightX) && (bottomY <= glY) && (glY <= topY))
		{
			Runnable runnable = () -> {};
			onClick = new _MouseOver<>(null, new EventHandler(runnable, runnable, runnable));
		}
		// Draw the title.
		_drawLabel(leftX, topY - 0.1f, leftX + 0.5f, topY, title.toUpperCase());
		
		float yOffset = topY - (rowHeight + rowSpace);
		for (T value : values)
		{
			float xOffset = leftX;
			for (_RenderTuple<T> renderer : columns)
			{
				float bottom = yOffset - rowHeight;
				float right = xOffset + renderer.width;
				boolean isMouseOver = ((xOffset <= glX) && (glX <= right) && (bottom <= glY) && (glY <= yOffset));
				Runnable runnable = renderer.draw.render(xOffset, bottom, right, yOffset, isMouseOver, value);
				if (null != runnable)
				{
					onClick = new _MouseOver<>(value, new EventHandler(runnable, runnable, runnable));
				}
				xOffset = right + renderer.spacing;
			}
			yOffset -= (rowHeight + rowSpace);
		}
		return onClick;
	}

	private String _getInventoryName(boolean fuelMode)
	{
		String name;
		if (null == _openInventoryLocation)
		{
			name = "Floor";
		}
		else
		{
			BlockProxy proxy = _blockLoader.apply(_openInventoryLocation);
			Block block = proxy.getBlock();
			if (_environment.blocks.CRAFTING_TABLE == block)
			{
				name = "Crafting Table";
			}
			else if (_environment.blocks.FURNACE == block)
			{
				if (fuelMode)
				{
					name = "Furnace Fuel";
				}
				else
				{
					name = "Furnace";
				}
			}
			else
			{
				// Future items may appear here.
				name = "(Unknown)";
			}
		}
		return name;
	}

	private void _drawHover(float glX, float glY, String name)
	{
		float labelHeight = 0.1f;
		float labelWidth = 0.2f;
		
		float left = glX;
		float bottom = glY - labelHeight;
		float right = glX + labelWidth;
		float top = glY;
		_drawBackground(left, bottom, right, top, false);
		_drawLabel(left, bottom, right, top, name.toUpperCase());
	}


	public static record EventHandler(Runnable click
			, Runnable rightClick
			, Runnable shiftClick
	) {}

	private static enum _WindowMode
	{
		// No windows visible.
		NONE,
		// We want to see our inventory, the floor, and the crafting panel.
		FLOOR,
		// We want to see our inventory, a selected block, and the crafting panel.
		CRAFTING_TABLE_INVENTORY,
		// We want to see our inventory, a selected block, but only the special crafting panel which we can't operate.
		FURNACE_INVENTORY,
		// We want to see our inventory and the fuel inventory of a block but no crafting panel.
		FUEL,
		;
		
		public boolean usesBlock()
		{
			return (this == CRAFTING_TABLE_INVENTORY)
					|| (this == FURNACE_INVENTORY)
					|| (this == FUEL)
			;
		}
		public boolean isCorrectBlock(Environment environment, Block block)
		{
			boolean isCorrect;
			switch (this)
			{
			case CRAFTING_TABLE_INVENTORY:
				isCorrect = (environment.blocks.CRAFTING_TABLE == block);
				break;
			case FURNACE_INVENTORY:
			case FUEL:
				isCorrect = (environment.blocks.FURNACE == block);
				break;
				default:
					// We shouldn't be asking in this case.
					throw Assert.unreachable();
			}
			return isCorrect;
		}
	}

	private static record _RenderTuple<T>(_ValueRenderer<T> draw, float width, float spacing)
	{
	}

	private interface _ValueRenderer<T>
	{
		Runnable render(float left, float bottom, float right, float top, boolean isMouseOver, T value);
	}

	private static record _MouseOver<T>(T context
			, EventHandler handler
	) {}
}
