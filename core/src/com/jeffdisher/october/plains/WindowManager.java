package com.jeffdisher.october.plains;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.aspects.CraftAspect;
import com.jeffdisher.october.aspects.Environment;
import com.jeffdisher.october.aspects.StationRegistry;
import com.jeffdisher.october.data.BlockProxy;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.Block;
import com.jeffdisher.october.types.BodyPart;
import com.jeffdisher.october.types.Craft;
import com.jeffdisher.october.types.CraftOperation;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.FuelState;
import com.jeffdisher.october.types.Inventory;
import com.jeffdisher.october.types.Item;
import com.jeffdisher.october.types.Items;
import com.jeffdisher.october.types.MutableInventory;
import com.jeffdisher.october.types.NonStackableItem;
import com.jeffdisher.october.utils.Assert;


/**
 * This class manages the various overlay windows in the system.
 */
public class WindowManager
{
	public static final float NO_PROGRESS = 0.0f;
	public static final float OUTLINE_SIZE = 0.01f;

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

	// We use 2 buffers here, but they only differ in that the tile buffer knows the texture atlas size while the label buffer is 1.0/1.0.
	private int _atlasVertexBuffer;
	private int _unitVertexBuffer;

	private int _backgroundFrameTexture;
	private int _backgroundSpaceTexture;
	private int _highlightTexture;
	private int _progressTexture;
	private Entity _entity;

	private _WindowMode _mode;

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
						+ "varying vec2 vTexture;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	vec2 texCoord = vec2(clamp(vTexture.x + uTextureBase.x, 0.0, 1.0), clamp(vTexture.y + uTextureBase.y, 0.0, 1.0));\n"
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
		
		// We need to create the vertex buffers for the tile and the label.
		_atlasVertexBuffer = _defineCommonVertices(_gl, _atlas.tileCoordinateSize);
		_unitVertexBuffer = _defineCommonVertices(_gl, 1.0f);
		
		// Create the special textures for the window areas (just one pixel allowing us to avoid creating a new shader).
		ByteBuffer textureBufferData = ByteBuffer.allocateDirect(2);
		textureBufferData.order(ByteOrder.nativeOrder());
		
		// The frame is opaque light grey.
		_backgroundFrameTexture = _gl.glGenTexture();
		textureBufferData.put(new byte[] { (byte) 180, (byte)255 });
		((java.nio.Buffer) textureBufferData).flip();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _backgroundFrameTexture);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_LUMINANCE_ALPHA, 1, 1, 0, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		
		// The space is dark grey with alpha.
		_backgroundSpaceTexture = _gl.glGenTexture();
		textureBufferData.put(new byte[] { 32, (byte)196 });
		((java.nio.Buffer) textureBufferData).flip();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _backgroundSpaceTexture);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_LUMINANCE_ALPHA, 1, 1, 0, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		
		// The highlight is light grey with alpha.
		_highlightTexture = _gl.glGenTexture();
		((java.nio.Buffer) textureBufferData).position(0);
		textureBufferData.put(new byte[] { (byte)128, (byte)196 });
		((java.nio.Buffer) textureBufferData).flip();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _highlightTexture);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_LUMINANCE_ALPHA, 1, 1, 0, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		
		// Create the progress texture - this is green with a low alpha since we draw it on top.
		_progressTexture = _gl.glGenTexture();
		textureBufferData = ByteBuffer.allocateDirect(4);
		textureBufferData.order(ByteOrder.nativeOrder());
		textureBufferData.put(new byte[] { (byte)0, (byte)255, (byte)0, (byte)128 });
		((java.nio.Buffer) textureBufferData).flip();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _progressTexture);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, 1, 1, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		
		// By default, we don't have any window mode active.
		_mode = null;
	}

	public EventHandler drawWindowsWithButtonCapture(ClientLogic client, float glX, float glY)
	{
		// Enable our program
		_gl.glUseProgram(_program);
		
		// Draw the data associated with an entity which is always on screen.
		if (null != _entity)
		{
			_drawHotbar();
			_drawEntityMetaData(_entity);
		}
		
		// Handle the case where we might need to close the inventory window if the block was destroyed or we are too far away.
		AbsoluteLocation openInventoryLocation = (null != _mode) ? _mode.selectedStation : null;
		if (null != openInventoryLocation)
		{
			int absX = Math.abs(openInventoryLocation.x() - Math.round(_entity.location().x()));
			int absY = Math.abs(openInventoryLocation.y() - Math.round(_entity.location().y()));
			int absZ = Math.abs(openInventoryLocation.z() - Math.round(_entity.location().z()));
			boolean isLocationClose = ((absX <= 2) && (absY <= 2) && (absZ <= 2));
			if (!isLocationClose)
			{
				_mode = null;
			}
		}
		
		// See if we should show the inventory window.
		// (note that the entity could only be null during start-up).
		EventHandler button = null;
		if ((null != _mode) && (null != _entity))
		{
			// Draw the entity inventory.
			Inventory entityInventory = _entity.inventory();
			BlockProxy proxy = (null != _mode.selectedStation) ? _blockLoader.apply(openInventoryLocation) : null;
			Inventory blockInventory = (null == _mode.selectedStation)
					? _feetBlockInventory()
					: _selectedBlockInventory(proxy, _mode.inFuelInventory)
			;
			
			_MouseOver<Integer> overKey = _drawEntityInventory(client, _entity.armourSlots(), entityInventory, blockInventory, glX, glY);
			if (null != overKey)
			{
				if (null != overKey.context)
				{
					Items items = _synthesizeItems(entityInventory, overKey.context);
					Item type = items.type();
					_drawHover(glX, glY, type.name());
				}
				button = overKey.handler;
			}
			
			// Draw the inventory of the ground or selected block.
			if (null != blockInventory)
			{
				String inventoryName = _getInventoryName(_mode.selectedStation, _mode.inFuelInventory);
				_MouseOver<Integer> thisButton = _drawBlockInventory(client, blockInventory, inventoryName, glX, glY);
				if (null != thisButton)
				{
					if (null != thisButton.context)
					{
						Items items = _synthesizeItems(blockInventory, thisButton.context);
						Item type = items.type();
						_drawHover(glX, glY, type.name());
					}
					button = thisButton.handler;
				}
				
				// If there is an active fuel aspect to this (no matter the display node, draw it).
				if (null != _mode.selectedStation)
				{
					FuelState fuel = proxy.getFuel();
					if ((null != fuel) && (null != fuel.currentFuel()))
					{
						Item current = fuel.currentFuel();
						float progressBar = (float)fuel.millisFueled() / (float)_environment.fuel.millisOfFuel(current);
						// TODO:  We really need a better solution than these hard-coded positions, everywhere.
						_drawItem(current, 0, -0.45f, - 0.15f, 0.1f, false, progressBar);
					}
				}
			}
			
			// Draw the crafting panel - this will appear when no block is selected or when the selected block has crafting classifications.
			Set<Craft.Classification> selectedBlockClassifications = (null != _mode.selectedStation)
					? _environment.stations.getCraftingClasses(proxy.getBlock())
					: Set.of()
			;
			boolean selectedBlockCanCraft = !selectedBlockClassifications.isEmpty();
			if ((null == _mode.selectedStation) || selectedBlockCanCraft)
			{
				CraftOperation crafting = (null != _mode.selectedStation)
						? proxy.getCrafting()
						: _entity.localCraftOperation()
				;
				Inventory craftingInventory = (null != _mode.selectedStation)
						? blockInventory
						: entityInventory
				;
				Set<Craft.Classification> classifications = (null != _mode.selectedStation)
						? selectedBlockClassifications
						: Set.of(Craft.Classification.TRIVIAL)
				;
				boolean canManuallyCraft = (null != _mode.selectedStation)
						? (_environment.stations.getManualMultiplier(proxy.getBlock()) > 0)
						: true
				;
				_MouseOver<Craft> thisButton = _drawCraftingPanel(client, crafting, craftingInventory, classifications, canManuallyCraft, glX, glY);
				if (null != thisButton)
				{
					// The context will be provided if we are hovering over a craft (even if it isn't possible - just not the panel background).
					if (null != thisButton.context)
					{
						_drawCraftingHover(glX, glY, thisButton.context);
					}
					// The handler will be null if there is no valid crafting operation.
					button = thisButton.handler;
				}
			}
		}
		
		// Allow any periodic cleanup.
		_textManager.allowTexturePurge();
		
		return button;
	}

	private void _drawHotbar()
	{
		float hotbarScale = 0.1f;
		float hotbarSpacing = 0.05f;
		float hotbarBottom = -0.95f;
		float hotbarWidth = ((float)Entity.HOTBAR_SIZE * hotbarScale) + ((float)(Entity.HOTBAR_SIZE - 1) * hotbarSpacing);
		float nextLeftButton = - hotbarWidth / 2.0f;
		for (int i = 0; i < _entity.hotbarItems().length; ++i)
		{
			// Get the inventory key (0 if not nothing here).
			int key = _entity.hotbarItems()[i];
			
			// Find the item type.
			Item type;
			int count;
			if (Entity.NO_SELECTION != key)
			{
				// This is a real item so find out its type
				Inventory entityInventory = _entity.inventory();
				Items stack = entityInventory.getStackForKey(key);
				NonStackableItem nonStack = entityInventory.getNonStackableForKey(key);
				
				// This must be there if selected.
				Assert.assertTrue((null != stack) != (null != nonStack));
				type = (null != stack) ? stack.type() : nonStack.type();
				count = (null != stack) ? stack.count() : 0;
			}
			else
			{
				// There is nothing here - TODO:  Don't use this "air hack".
				type = _environment.special.AIR.item();
				count = 0;
			}
			
			// Determine if this is selected.
			boolean isSelected = (_entity.hotbarIndex() == i);
			float progress = isSelected ? 1.0f : 0.0f;
			_drawBackground(nextLeftButton, hotbarBottom, nextLeftButton + hotbarScale, hotbarBottom + hotbarScale);
			_drawPrimaryTileAndNumber(type, count, nextLeftButton, hotbarBottom, hotbarScale, progress);
			nextLeftButton += hotbarScale + hotbarSpacing;
		}
	}

	private void _drawEntityMetaData(Entity thisEntity)
	{
		float labelWidth = 0.1f;
		float labelMargin = 0.80f;
		float valueMargin = labelMargin + labelWidth;
		
		_drawLabel(labelMargin, -0.85f, -0.80f, "Health");
		_drawLabel(valueMargin, -0.85f, -0.80f, Byte.toString(thisEntity.health()));
		
		_drawLabel(labelMargin, -0.90f, -0.85f, "Food");
		_drawLabel(valueMargin, -0.90f, -0.85f, Byte.toString(thisEntity.food()));
		
		// We want to show breath as a percentage but it is normally out of 1000.
		_drawLabel(labelMargin, -0.95f, -0.90f, "Breath");
		_drawLabel(valueMargin, -0.95f, -0.90f, Integer.toString(thisEntity.breath() / 10));
		
		_drawLabel(labelMargin, -1.0f, -0.95f, "z-level");
		String zLevel = String.format("%.2f", thisEntity.location().z());
		_drawLabel(valueMargin, -1.0f, -0.95f, zLevel);
	}

	private _MouseOver<Integer> _drawEntityInventory(ClientLogic client, NonStackableItem[] armour, Inventory entityInventory, Inventory blockInventory, float glX, float glY)
	{
		// First, draw the armour slots - these are on the top-right of the screen..
		float slotScale = 0.1f;
		float slotSpacing = 0.05f;
		float slotRight = 0.95f;
		float nextTopSlot = 0.95f;
		_MouseOver<Integer> mouseHandler = null;
		for (int i = 0; i < armour.length; ++i)
		{
			NonStackableItem piece = armour[i];
			float left = slotRight - slotScale;
			float bottom = nextTopSlot - slotScale;
			boolean highlight = false;
			if ((left <= glX) && (glX <= slotRight) && (bottom <= glY) && (glY <= nextTopSlot))
			{
				int thisIndex = i;
				Runnable swap = () -> {
					// Swap the inventory for this slot.
					int selectedItem = _entity.hotbarItems()[_entity.hotbarIndex()];
					NonStackableItem nonStack = _entity.inventory().getNonStackableForKey(selectedItem);
					Item type = (null != nonStack) ? nonStack.type() : null;
					BodyPart thisButtonPart = BodyPart.values()[thisIndex];
					BodyPart armourPart = _environment.armour.getBodyPart(type);
					// We want to allow the swap if we can swap this in _or_ if we can swap to empty.
					if ((thisButtonPart == armourPart) || (0 == selectedItem))
					{
						// This is the kind of swap we can do.
						client.swapArmour(thisButtonPart, selectedItem);
					}
				};
				
				Runnable doNothing = () -> {};
				mouseHandler = new _MouseOver<>(null, new EventHandler(swap, doNothing, doNothing));
				highlight = true;
			}
			_drawBackground(left, bottom, slotRight, nextTopSlot);
			if (null != piece)
			{
				Item type = piece.type();
				int maxDurability = _environment.durability.getDurability(type);
				float progress = ((float)piece.durability()) / ((float)maxDurability);
				_drawPrimaryTileAndNumber(type, 0, left, bottom, slotScale, progress);
			}
			if (highlight)
			{
				// If we want to highlight this, draw the highlight square over this.
				_gl.glActiveTexture(GL20.GL_TEXTURE0);
				_gl.glBindTexture(GL20.GL_TEXTURE_2D, _highlightTexture);
				_drawUnitRect(left, bottom, slotRight, nextTopSlot);
			}
			nextTopSlot -= slotScale + slotSpacing;
		}
		
		// Now, draw the main inventory.
		_ValueRenderer<Integer> keyRender = (float left, float bottom, float scale, boolean isMouseOver, Integer key) -> {
			// See if this is stackable or not.
			Items stack = entityInventory.getStackForKey(key);
			NonStackableItem nonStack = entityInventory.getNonStackableForKey(key);
			Item item = (null != stack) ? stack.type() : nonStack.type();
			int count = (null != stack) ? stack.count() : 0;
			float progress;
			if (null != stack)
			{
				progress = NO_PROGRESS;
			}
			else
			{
				// We will draw the durability as a progress over the non-stackable.
				int maxDurability = _environment.durability.getDurability(item);
				progress = ((float)nonStack.durability()) / ((float)maxDurability);
			}
			_drawItem(item, count, left, bottom, scale, isMouseOver, progress);
			
			EventHandler onClick = null;
			if (isMouseOver)
			{
				Runnable click = () -> {
					// Select.
					// If this already was selected, clear it.
					if (_entity.hotbarItems()[_entity.hotbarIndex()] == key)
					{
						client.setSelectedItem(0);
					}
					else
					{
						client.setSelectedItem(key);
					}
				};
				Runnable rightClick = () -> {
					// Transfer 1.
					AbsoluteLocation location = (null != _mode.selectedStation) ? _mode.selectedStation : GeometryHelpers.getCentreAtFeet(_entity);
					client.pushItemsToTileInventory(location, key, 1, _mode.inFuelInventory);
				};
				Runnable shiftClick = () -> {
					// Transfer all.
					// Find out how many can fit in the block.
					MutableInventory checker = new MutableInventory((null != blockInventory) ? blockInventory : Inventory.start(StationRegistry.CAPACITY_BLOCK_EMPTY).finish());
					int max = checker.maxVacancyForItem(item);
					int toDrop = Math.min(count, max);
					if (toDrop > 0)
					{
						AbsoluteLocation location = (null != _mode.selectedStation) ? _mode.selectedStation : GeometryHelpers.getCentreAtFeet(_entity);
						client.pushItemsToTileInventory(location, key, toDrop, _mode.inFuelInventory);
					}
				};
				onClick = new EventHandler(click, rightClick, shiftClick);
			}
			return onClick;
		};
		float inventoryRight = slotRight - slotScale - slotSpacing;
		_MouseOver<Integer> windowHandler = _drawTableWindow("Inventory", 0.05f, 0.05f, inventoryRight, 0.95f, glX, glY, 0.1f, 0.05f, entityInventory.sortedKeys(), keyRender);
		if (null != windowHandler)
		{
			mouseHandler = windowHandler;
		}
		return mouseHandler;
	}

	private _MouseOver<Integer> _drawBlockInventory(ClientLogic client, Inventory blockInventory, String inventoryName, float glX, float glY)
	{
		_ValueRenderer<Integer> keyRender = (float left, float bottom, float scale, boolean isMouseOver, Integer key) -> {
			// See if this is stackable or not.
			Items stack = blockInventory.getStackForKey(key);
			NonStackableItem nonStack = blockInventory.getNonStackableForKey(key);
			Item item = (null != stack) ? stack.type() : nonStack.type();
			int count = (null != stack) ? stack.count() : 0;
			float progress;
			if (null != stack)
			{
				progress = NO_PROGRESS;
			}
			else
			{
				// We will draw the durability as a progress over the non-stackable.
				int maxDurability = _environment.durability.getDurability(item);
				progress = ((float)nonStack.durability()) / ((float)maxDurability);
			}
			_drawItem(item, count, left, bottom, scale, isMouseOver, progress);
			
			EventHandler onClick = null;
			if (isMouseOver)
			{
				Runnable click = () -> {
					// Select.
					// Do nothing - this has no concept of selection.
				};
				Runnable rightClick = () -> {
					// Transfer 1.
					AbsoluteLocation location = (null != _mode.selectedStation) ? _mode.selectedStation : GeometryHelpers.getCentreAtFeet(_entity);
					client.pullItemsFromTileInventory(location, key, 1, _mode.inFuelInventory);
				};
				Runnable shiftClick = () -> {
					// Transfer all.
					// Find out how many we can hold.
					MutableInventory checker = new MutableInventory(_entity.inventory());
					int max = checker.maxVacancyForItem(item);
					int toPickUp = Math.min(count, max);
					if (toPickUp > 0)
					{
						AbsoluteLocation location = (null != _mode.selectedStation) ? _mode.selectedStation : GeometryHelpers.getCentreAtFeet(_entity);
						client.pullItemsFromTileInventory(location, key, toPickUp, _mode.inFuelInventory);
					}
				};
				onClick = new EventHandler(click, rightClick, shiftClick);
			}
			return onClick;
		};
		return _drawTableWindow(inventoryName, -0.95f, -0.80f, 0.95f, -0.05f, glX, glY, 0.1f, 0.05f, blockInventory.sortedKeys(), keyRender);
	}

	private _MouseOver<Craft> _drawCraftingPanel(ClientLogic client, CraftOperation crafting, Inventory craftingInventory, Set<Craft.Classification> classifications, boolean canManuallyCraft, float glX, float glY)
	{
		// Note that the crafting panel will act a bit differently whether it is the player's inventory or a station (where even crafting table and furnace behave differently).
		_ValueRenderer<Craft> itemRender = (float left, float bottom, float scale, boolean isMouseOver, Craft craft) -> {
			// We will only check the highlight if this is something we even could craft.
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
			// We will only show the first output type so see how many there are.
			Item type = craft.output[0];
			int count = 0;
			for (Item item : craft.output)
			{
				if (type == item)
				{
					count += 1;
				}
			}
			_drawItem(type, count, left, bottom, scale, shouldHighlight, progressBar);
			EventHandler onClick = null;
			if (shouldHighlight)
			{
				Runnable doCraft;
				if (null != _mode.selectedStation)
				{
					// Craft in table.
					doCraft = () -> {
						if (CraftAspect.canApply(craft, craftingInventory))
						{
							client.beginCraftInBlock(_mode.selectedStation, craft);
						}
					};
				}
				else
				{
					// Craft in inventory.
					doCraft = () -> {
						if (CraftAspect.canApply(craft, craftingInventory))
						{
							client.beginCraft(craft);
						}
					};
				}
				// For now, at least, just treat all the events the same way.
				onClick = new EventHandler(doCraft, doCraft, doCraft);
			}
			return onClick;
		};
		return _drawTableWindow("Crafting", -0.95f, 0.05f, -0.05f, 0.95f, glX, glY, 0.1f, 0.05f, _environment.crafting.craftsForClassifications(classifications), itemRender);
	}

	public void setEntity(Entity entity)
	{
		_entity = entity;
	}

	public void toggleInventory()
	{
		if (null != _mode)
		{
			_mode = null;
		}
		else
		{
			_mode = new _WindowMode(null, false);
		}
	}

	public void toggleFuelInventory()
	{
		// We want to see if the block even has a fuel inventory.
		if ((null != _mode) && (null != _mode.selectedStation))
		{
			BlockProxy proxy = _blockLoader.apply(_mode.selectedStation);
			if (null != proxy.getFuel())
			{
				_mode = new _WindowMode(_mode.selectedStation, !_mode.inFuelInventory);
			}
		}
	}

	public void closeAllWindows()
	{
		if (null != _mode)
		{
			_mode = null;
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
		if (_environment.stations.getNormalInventorySize(block) > 0)
		{
			// We are at least some kind of station with an inventory.
			_mode = new _WindowMode(blockLocation, false);
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
		// See if we have some block selected.
		if ((null != _mode) && (null != _mode.selectedStation))
		{
			// See if this block has an active crafting operation and is manually operated.
			BlockProxy proxy = _blockLoader.apply(_mode.selectedStation);
			if ((null != proxy.getCrafting()) && (_environment.stations.getManualMultiplier(proxy.getBlock()) > 0))
			{
				location = _mode.selectedStation;
			}
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

	private void _drawItem(Item selectedItem, int count, float left, float bottom, float scale, boolean shouldHighlight, float progressBar)
	{
		// This basic item case is square so just build the top-right edges.
		float right = left + scale;
		float top = bottom + scale;
		
		// Draw the background.
		_drawBackground(left, bottom, right, top);
		
		_drawPrimaryTileAndNumber(selectedItem, count, left, bottom, scale, progressBar);
		if (shouldHighlight)
		{
			// If we want to highlight this, draw the highlight square over this.
			_gl.glActiveTexture(GL20.GL_TEXTURE0);
			_gl.glBindTexture(GL20.GL_TEXTURE_2D, _highlightTexture);
			_drawUnitRect(left, bottom, right, top);
		}
	}

	private void _drawPrimaryTileAndNumber(Item item, int count, float left, float bottom, float scale, float progressBar)
	{
		// Draw the tile.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _atlas.tileTextures);
		float[] uv = _atlas.baseOfTileTexture(item);
		float textureBaseU = uv[0];
		float textureBaseV = uv[1];
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, left, bottom);
		// We want to just draw this square.
		_gl.glUniform2f(_uScale, scale, scale);
		_gl.glUniform2f(_uTextureBase, textureBaseU, textureBaseV);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _atlasVertexBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
		
		// Draw the number in the corner (only if it is non-zero).
		if (count > 0)
		{
			TextManager.Element element = _textManager.lazilyLoadStringTexture(Integer.toString(count));
			_gl.glActiveTexture(GL20.GL_TEXTURE0);
			_gl.glBindTexture(GL20.GL_TEXTURE_2D, element.textureObject());
			_gl.glUniform1i(_uTexture, 0);
			_gl.glUniform2f(_uOffset, left, bottom);
			_gl.glUniform2f(_uScale, 0.5f * scale * element.aspectRatio(), 0.5f * scale);
			_gl.glUniform2f(_uTextureBase, 0.0f, 0.0f);
			_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _unitVertexBuffer);
			_gl.glEnableVertexAttribArray(0);
			_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
			_gl.glEnableVertexAttribArray(1);
			_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
			_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
		}
		
		// If there is a progress bar, draw that on top (vertical - from bottom to top).
		if (progressBar > 0.0f)
		{
			// There is a progress bar, so draw that, instead.
			float progressTop = bottom + (scale * progressBar);
			_gl.glActiveTexture(GL20.GL_TEXTURE0);
			_gl.glBindTexture(GL20.GL_TEXTURE_2D, _progressTexture);
			_drawUnitRect(left, bottom, left + scale, progressTop);
		}
	}

	private Inventory _feetBlockInventory()
	{
		AbsoluteLocation block = GeometryHelpers.getCentreAtFeet(_entity);
		BlockProxy proxy = _blockLoader.apply(block);
		// Be wary that this may not yet be loaded.
		return (null != proxy)
				? proxy.getInventory()
				: null
		;
	}

	private static Inventory _selectedBlockInventory(BlockProxy proxy, boolean fuelMode)
	{
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

	private void _drawLabel(float left, float bottom, float top, String label)
	{
		TextManager.Element element = _textManager.lazilyLoadStringTexture(label);
		float textureAspect = element.aspectRatio();
		float right = left + textureAspect * (top - bottom);
		_drawTextElement(left, bottom, right, top, element.textureObject());
	}

	private void _drawTextElement(float left, float bottom, float right, float top, int labelTexture)
	{
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, labelTexture);
		_drawUnitRect(left, bottom, right, top);
	}

	private void _drawBackground(float left, float bottom, float right, float top)
	{
		// We want draw the frame and then the space on top of that.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _backgroundFrameTexture);
		_drawUnitRect(left - OUTLINE_SIZE, bottom - OUTLINE_SIZE, right + OUTLINE_SIZE, top + OUTLINE_SIZE);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _backgroundSpaceTexture);
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

	private <T> _MouseOver<T> _drawTableWindow(String title, float leftX, float bottomY, float rightX, float topY, float glX, float glY, float elementSize, float margin, List<T> values, _ValueRenderer<T> renderer)
	{
		_MouseOver<T> onClick = null;
		// Draw the window outline and create a default catch runnable to block the background.
		_drawBackground(leftX, bottomY, rightX, topY);
		if ((leftX <= glX) && (glX <= rightX) && (bottomY <= glY) && (glY <= topY))
		{
			Runnable runnable = () -> {};
			onClick = new _MouseOver<>(null, new EventHandler(runnable, runnable, runnable));
		}
		// Draw the title.
		_drawLabel(leftX, topY - 0.1f, topY, title.toUpperCase());
		
		// We want to draw these in a grid, in rows.  Leave space for margins.
		float xSpace = rightX - leftX - (2.0f * margin);
		// The size of each item is the margin before the element and the element itself.
		float spacePerElement = elementSize + margin;
		int itemsPerRow = (int) Math.round(Math.floor(xSpace / spacePerElement));
		int xElement = 0;
		int yElement = 0;
		
		float leftMargin = leftX + margin;
		// Leave space for top margin and title.
		float topMargin = topY - spacePerElement - margin;
		for (T value : values)
		{
			// Find the bottom-left of this item.
			float left = leftMargin + (xElement * spacePerElement);
			float bottom = topMargin - (yElement * spacePerElement) - elementSize;
			float top = bottom + elementSize;
			float right = left + elementSize;
			boolean isMouseOver = ((left <= glX) && (glX <= right) && (bottom <= glY) && (glY <= top));
			EventHandler handler = renderer.render(left, bottom, elementSize, isMouseOver, value);
			if (isMouseOver)
			{
				onClick = new _MouseOver<>(value, handler);
			}
			xElement += 1;
			if (xElement >= itemsPerRow)
			{
				xElement = 0;
				yElement += 1;
			}
		}
		return onClick;
	}

	private String _getInventoryName(AbsoluteLocation openInventoryLocation, boolean fuelMode)
	{
		String name;
		if (null == openInventoryLocation)
		{
			name = "Floor";
		}
		else
		{
			BlockProxy proxy = _blockLoader.apply(openInventoryLocation);
			Block block = proxy.getBlock();
			name = block.item().name();
			if (fuelMode)
			{
				name += " Fuel";
			}
		}
		return name;
	}

	private void _drawHover(float glX, float glY, String name)
	{
		TextManager.Element element = _textManager.lazilyLoadStringTexture(name.toUpperCase());
		float labelHeight = 0.1f;
		
		float left = glX;
		float bottom = glY - labelHeight;
		float top = glY;
		float right = left + element.aspectRatio() * (top - bottom);
		_drawBackground(left, bottom, right, top);
		_drawTextElement(left, bottom, right, top, element.textureObject());
	}

	private static Items _synthesizeItems(Inventory inventory, int selectedKey)
	{
		// This helper will synthesize the non-stackable as a stack with 1 element, just as a stop-gap.
		Items stack = inventory.getStackForKey(selectedKey);
		Items items;
		if (null != stack)
		{
			items = stack;
		}
		else
		{
			NonStackableItem nonStack = inventory.getNonStackableForKey(selectedKey);
			if (null != nonStack)
			{
				items = new Items(nonStack.type(), 1);
			}
			else
			{
				items = null;
			}
		}
		return items;
	}

	private void _drawCraftingHover(float glX, float glY, Craft craft)
	{
		String name = craft.output[0].name();
		TextManager.Element element = _textManager.lazilyLoadStringTexture(name.toUpperCase());
		float labelHeight = 0.1f;
		float labelWidth = element.aspectRatio() * labelHeight;
		
		// We want to show the crafting ingredients in a row under the title.
		float itemSize = 0.1f;
		float itemMargin = 0.05f;
		float ingredientsWidth = itemMargin + (craft.input.length * (itemSize + itemMargin));
		//_drawItem(Item selectedItem, int count, float left, float bottom, float scale, boolean shouldHighlight, float progressBar)
		
		float left = glX;
		float windowBottom = glY - labelHeight - itemSize - (2.0f * itemMargin);
		float textBottom = glY - labelHeight;
		float top = glY;
		float windowRight = left + Math.max(labelWidth, ingredientsWidth);
		float textRight = left + labelWidth;
		_drawBackground(left, windowBottom, windowRight, top);
		_drawTextElement(left, textBottom, textRight, top, element.textureObject());
		
		float inputLeft = left + itemMargin;
		float inputTop = textBottom - itemMargin;
		float inputBottom = inputTop - itemSize;
		for (Items items : craft.input)
		{
			_drawItem(items.type(), items.count(), inputLeft, inputBottom, itemSize, false, 0.0f);
			inputLeft += itemSize + itemMargin;
		}
	}


	public static record EventHandler(Runnable click
			, Runnable rightClick
			, Runnable shiftClick
	) {}

	// selectedStation can be null if we have windows open but are looking at the floor.
	private static record _WindowMode(AbsoluteLocation selectedStation, boolean inFuelInventory)
	{}

	private interface _ValueRenderer<T>
	{
		EventHandler render(float left, float bottom, float scale, boolean isMouseOver, T value);
	}

	private static record _MouseOver<T>(T context
			, EventHandler handler
	) {}
}
