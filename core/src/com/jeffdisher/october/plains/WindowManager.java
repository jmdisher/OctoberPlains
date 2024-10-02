package com.jeffdisher.october.plains;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.aspects.CraftAspect;
import com.jeffdisher.october.aspects.Environment;
import com.jeffdisher.october.data.BlockProxy;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.Block;
import com.jeffdisher.october.types.BodyPart;
import com.jeffdisher.october.types.Craft;
import com.jeffdisher.october.types.CraftOperation;
import com.jeffdisher.october.types.CreativeInventory;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.FuelState;
import com.jeffdisher.october.types.Inventory;
import com.jeffdisher.october.types.Item;
import com.jeffdisher.october.types.Items;
import com.jeffdisher.october.types.NonStackableItem;
import com.jeffdisher.october.utils.Assert;


/**
 * This class manages the various overlay windows in the system.
 */
public class WindowManager
{
	public static final float NO_PROGRESS = 0.0f;
	public static final float OUTLINE_SIZE = 0.01f;
	
	public static final float ARMOUR_SLOT_SCALE = 0.1f;
	public static final float ARMOUR_SLOT_SPACING = 0.05f;
	public static final float ARMOUR_SLOT_RIGHT_EDGE = 0.95f;
	public static final float ARMOUR_SLOT_TOP_EDGE = 0.95f;
	public static final float WINDOW_TITLE_HEIGHT = 0.1f;
	public static final _WindowDimensions WINDOW_TOP_LEFT = new _WindowDimensions(-0.95f, 0.05f, -0.05f, 0.95f, 0.1f, 0.05f);
	public static final _WindowDimensions WINDOW_TOP_RIGHT = new _WindowDimensions(0.05f, 0.05f, ARMOUR_SLOT_RIGHT_EDGE - ARMOUR_SLOT_SCALE - ARMOUR_SLOT_SPACING, 0.95f, 0.1f, 0.05f);
	public static final _WindowDimensions WINDOW_BOTTOM = new _WindowDimensions(-0.95f, -0.80f, 0.95f, -0.05f, 0.1f, 0.05f);

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

	private int _backgroundFrameTexture_Common;
	private int _backgroundFrameTexture_Green;
	private int _backgroundFrameTexture_Red;
	private int _backgroundSpaceTexture;
	private int _highlightTexture;
	private int _progressTexture;
	private Entity _authoritativeEntity;
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
		ByteBuffer textureBufferData = ByteBuffer.allocateDirect(4);
		textureBufferData.order(ByteOrder.nativeOrder());
		
		// The "common" frame is opaque light grey.
		_backgroundFrameTexture_Common = _gl.glGenTexture();
		textureBufferData.put(new byte[] { (byte) 180, (byte)255 });
		((java.nio.Buffer) textureBufferData).flip();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _backgroundFrameTexture_Common);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_LUMINANCE_ALPHA, 1, 1, 0, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		((java.nio.Buffer) textureBufferData).clear();
		
		// We also have red and green frame colours.
		_backgroundFrameTexture_Red = _gl.glGenTexture();
		textureBufferData.put(new byte[] { (byte) 255, (byte)0, (byte) 0, (byte)255 });
		((java.nio.Buffer) textureBufferData).flip();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _backgroundFrameTexture_Red);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, 1, 1, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		((java.nio.Buffer) textureBufferData).clear();
		_backgroundFrameTexture_Green = _gl.glGenTexture();
		textureBufferData.put(new byte[] { (byte) 0, (byte)255, (byte) 0, (byte)255 });
		((java.nio.Buffer) textureBufferData).flip();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _backgroundFrameTexture_Green);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, 1, 1, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		((java.nio.Buffer) textureBufferData).clear();
		
		// The space is dark grey with alpha.
		_backgroundSpaceTexture = _gl.glGenTexture();
		textureBufferData.put(new byte[] { 32, (byte)196 });
		((java.nio.Buffer) textureBufferData).flip();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _backgroundSpaceTexture);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_LUMINANCE_ALPHA, 1, 1, 0, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		((java.nio.Buffer) textureBufferData).clear();
		
		// The highlight is light grey with alpha.
		_highlightTexture = _gl.glGenTexture();
		textureBufferData.put(new byte[] { (byte)128, (byte)196 });
		((java.nio.Buffer) textureBufferData).flip();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _highlightTexture);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_LUMINANCE_ALPHA, 1, 1, 0, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		((java.nio.Buffer) textureBufferData).clear();
		
		// Create the progress texture - this is green with a low alpha since we draw it on top.
		_progressTexture = _gl.glGenTexture();
		textureBufferData.put(new byte[] { (byte)0, (byte)255, (byte)0, (byte)128 });
		((java.nio.Buffer) textureBufferData).flip();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, _progressTexture);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, 1, 1, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		((java.nio.Buffer) textureBufferData).clear();
		
		// By default, we don't have any window mode active.
		_mode = null;
	}

	public EventHandler drawWindowsWithButtonCapture(ClientLogic client, AbsoluteLocation worldMouseLocation, float glX, float glY)
	{
		// Enable our program
		_gl.glUseProgram(_program);
		
		// Draw the data associated with an entity which is always on screen.
		if (null != _entity)
		{
			_drawHotbar();
			_drawEntityMetaData();
		}
		
		// Draw the block name at the top of the screen.
		BlockProxy proxyUnderMouse = (null != worldMouseLocation) ? _blockLoader.apply(worldMouseLocation) : null;
		if (null != proxyUnderMouse)
		{
			Block blockUnderMouse = proxyUnderMouse.getBlock();
			if (_environment.special.AIR != blockUnderMouse)
			{
				Item itemUnderMouse = blockUnderMouse.item();
				_drawItem(itemUnderMouse, 0, _backgroundFrameTexture_Common, -0.1f, 0.85f, 0.1f, false, 0.0f);
				_drawTextOnBackground(0.05f, 0.95f, itemUnderMouse.name().toUpperCase());
			}
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
			Inventory entityInventory = _getEntityInventory();
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
					_drawTextOnBackground(glX, glY, type.name());
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
						_drawTextOnBackground(glX, glY, type.name());
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
						float progressBar = (float)fuel.millisFuelled() / (float)_environment.fuel.millisOfFuel(current);
						// TODO:  We really need a better solution than these hard-coded positions, everywhere.
						_drawItem(current, 0, _backgroundFrameTexture_Common, -0.45f, - 0.15f, 0.1f, false, progressBar);
					}
				}
			}
			
			// Draw the crafting panel - this will appear when no block is selected or when the selected block has crafting classifications.
			Set<String> selectedBlockClassifications = (null != _mode.selectedStation)
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
				Set<String> classifications = (null != _mode.selectedStation)
						? selectedBlockClassifications
						: Set.of(CraftAspect.BUILT_IN)
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
				Inventory entityInventory = _getEntityInventory();
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
			_drawBackground(_backgroundFrameTexture_Common, nextLeftButton, hotbarBottom, nextLeftButton + hotbarScale, hotbarBottom + hotbarScale);
			_drawPrimaryTileAndNumber(type, count, nextLeftButton, hotbarBottom, hotbarScale, progress);
			nextLeftButton += hotbarScale + hotbarSpacing;
		}
	}

	private void _drawEntityMetaData()
	{
		float labelWidth = 0.1f;
		float labelMargin = 0.80f;
		float valueMargin = labelMargin + labelWidth;
		
		// We will use the greater of authoritative and projected for most of these stats.
		// That way, we get the stability of the authoritative numbers but the quick response to eating/breathing actions)
		byte health = _authoritativeEntity.health();
		_drawLabel(labelMargin, -0.85f, -0.80f, "Health");
		_drawLabel(valueMargin, -0.85f, -0.80f, Byte.toString(health));
		
		byte food = (byte)Math.max(_authoritativeEntity.food(), _entity.food());
		_drawLabel(labelMargin, -0.90f, -0.85f, "Food");
		_drawLabel(valueMargin, -0.90f, -0.85f, Byte.toString(food));
		
		int breath = Math.max(_authoritativeEntity.breath(), _entity.breath());
		_drawLabel(labelMargin, -0.95f, -0.90f, "Breath");
		_drawLabel(valueMargin, -0.95f, -0.90f, Integer.toString(breath));
		
		_drawLabel(labelMargin, -1.0f, -0.95f, "z-level");
		String zLevel = String.format("%.2f", _entity.location().z());
		_drawLabel(valueMargin, -1.0f, -0.95f, zLevel);
	}

	private _MouseOver<Integer> _drawEntityInventory(ClientLogic client, NonStackableItem[] armour, Inventory entityInventory, Inventory blockInventory, float glX, float glY)
	{
		// First, draw the armour slots - these are on the top-right of the screen.
		float nextTopSlot = ARMOUR_SLOT_TOP_EDGE;
		_MouseOver<Integer> mouseHandler = null;
		for (int i = 0; i < armour.length; ++i)
		{
			NonStackableItem piece = armour[i];
			float left = ARMOUR_SLOT_RIGHT_EDGE - ARMOUR_SLOT_SCALE;
			float bottom = nextTopSlot - ARMOUR_SLOT_SCALE;
			boolean highlight = false;
			if (_isMouseOver(left, bottom, ARMOUR_SLOT_RIGHT_EDGE, nextTopSlot, glX, glY))
			{
				int thisIndex = i;
				Runnable swap = () -> {
					// Swap the inventory for this slot.
					int selectedItem = _entity.hotbarItems()[_entity.hotbarIndex()];
					NonStackableItem nonStack = _getEntityInventory().getNonStackableForKey(selectedItem);
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
			_drawBackground(_backgroundFrameTexture_Common, left, bottom, ARMOUR_SLOT_RIGHT_EDGE, nextTopSlot);
			if (null != piece)
			{
				Item type = piece.type();
				int maxDurability = _environment.durability.getDurability(type);
				float progress = ((float)piece.durability()) / ((float)maxDurability);
				_drawPrimaryTileAndNumber(type, 0, left, bottom, ARMOUR_SLOT_SCALE, progress);
			}
			if (highlight)
			{
				// If we want to highlight this, draw the highlight square over this.
				_gl.glActiveTexture(GL20.GL_TEXTURE0);
				_gl.glBindTexture(GL20.GL_TEXTURE_2D, _highlightTexture);
				_drawUnitRect(left, bottom, ARMOUR_SLOT_RIGHT_EDGE, nextTopSlot);
			}
			nextTopSlot -= ARMOUR_SLOT_SCALE + ARMOUR_SLOT_SPACING;
		}
		
		// Now, draw the main inventory.
		AbsoluteLocation location = (null != _mode.selectedStation) ? _mode.selectedStation : GeometryHelpers.getCentreAtFeet(_entity);
		ValueRenderer.DrawInventoryItem drawHelper = (Item selectedItem, int count, float left, float bottom, float scale, boolean shouldHighlight, int durability) ->
		{
			float progress = NO_PROGRESS;
			if (durability > 0)
			{
				// We will draw the durability as a progress over the non-stackable.
				int maxDurability = _environment.durability.getDurability(selectedItem);
				progress = ((float)durability) / ((float)maxDurability);
			}
			_drawItem(selectedItem, count, _backgroundFrameTexture_Common, left, bottom, scale, shouldHighlight, progress);
		};
		ValueRenderer<Integer> keyRender = ValueRenderer.buildEntityInventory(client, blockInventory, location, _entity, _mode.inFuelInventory, drawHelper);
		_MouseOver<Integer> windowHandler = _drawTableWindow("Inventory", WINDOW_TOP_RIGHT, _mode.topRight, glX, glY, entityInventory.sortedKeys(), keyRender);
		if (null != windowHandler)
		{
			mouseHandler = windowHandler;
		}
		return mouseHandler;
	}

	private _MouseOver<Integer> _drawBlockInventory(ClientLogic client, Inventory blockInventory, String inventoryName, float glX, float glY)
	{
		AbsoluteLocation location = (null != _mode.selectedStation) ? _mode.selectedStation : GeometryHelpers.getCentreAtFeet(_entity);
		ValueRenderer.DrawInventoryItem drawHelper = (Item selectedItem, int count, float left, float bottom, float scale, boolean shouldHighlight, int durability) ->
		{
			float progress = NO_PROGRESS;
			if (durability > 0)
			{
				// We will draw the durability as a progress over the non-stackable.
				int maxDurability = _environment.durability.getDurability(selectedItem);
				progress = ((float)durability) / ((float)maxDurability);
			}
			_drawItem(selectedItem, count, _backgroundFrameTexture_Common, left, bottom, scale, shouldHighlight, progress);
		};
		ValueRenderer<Integer> keyRender = ValueRenderer.buildBlockInventoryRenderer(client, blockInventory, location, _getEntityInventory(), _mode.inFuelInventory, drawHelper);
		return _drawTableWindow(inventoryName, WINDOW_BOTTOM, _mode.bottom, glX, glY, blockInventory.sortedKeys(), keyRender);
	}

	private _MouseOver<Craft> _drawCraftingPanel(ClientLogic client, CraftOperation crafting, Inventory craftingInventory, Set<String> classifications, boolean canManuallyCraft, float glX, float glY)
	{
		ValueRenderer.DrawCraftItem drawHelpers = (Item selectedItem, int count, float left, float bottom, float scale, boolean shouldHighlight, float progressBar, boolean isPossibleCraft) ->
		{
			// We will choose the outline texture based on whether or not this crafting recipe is available with the inventory (whether it is manual or not).
			int backgroundTexture = isPossibleCraft
					? _backgroundFrameTexture_Green
					: _backgroundFrameTexture_Red
			;
			_drawItem(selectedItem, count, backgroundTexture, left, bottom, scale, shouldHighlight, progressBar);
		};
		// Note that the crafting panel will act a bit differently whether it is the player's inventory or a station (where even crafting table and furnace behave differently).
		ValueRenderer<Craft> itemRender = ValueRenderer.buildCraftingRenderer(client, crafting, craftingInventory, _mode.selectedStation, canManuallyCraft, drawHelpers);
		return _drawTableWindow("Crafting", WINDOW_TOP_LEFT, _mode.topLeft, glX, glY, _environment.crafting.craftsForClassifications(classifications), itemRender);
	}

	public void setEntity(Entity authoritativeEntity, Entity projectedEntity)
	{
		_authoritativeEntity = authoritativeEntity;
		_entity = projectedEntity;
	}

	public void toggleInventory()
	{
		if (null != _mode)
		{
			_mode = null;
		}
		else
		{
			_mode = new _WindowMode(null, false, new _WindowState(), new _WindowState(), new _WindowState());
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
				// This requires a fresh bottom window state but we inherit the others.
				_mode = new _WindowMode(_mode.selectedStation, !_mode.inFuelInventory, _mode.topLeft, _mode.topRight, new _WindowState());
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
			_mode = new _WindowMode(blockLocation, false, new _WindowState(), new _WindowState(), new _WindowState());
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

	private void _drawItem(Item selectedItem, int count, int texture, float left, float bottom, float scale, boolean shouldHighlight, float progressBar)
	{
		// This basic item case is square so just build the top-right edges.
		float right = left + scale;
		float top = bottom + scale;
		
		// Draw the background.
		_drawBackground(texture, left, bottom, right, top);
		
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

	private void _drawBackground(int texture, float left, float bottom, float right, float top)
	{
		// We want draw the frame and then the space on top of that.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, texture);
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

	private <T> _MouseOver<T> _drawTableWindow(String title, _WindowDimensions dimensions, _WindowState state, float glX, float glY, List<T> values, ValueRenderer<T> renderer)
	{
		_MouseOver<T> onClick = null;
		// Draw the window outline and create a default catch runnable to block the background.
		_drawBackground(_backgroundFrameTexture_Common, dimensions.leftX, dimensions.bottomY, dimensions.rightX, dimensions.topY);
		if (_isMouseOver(dimensions.leftX, dimensions.bottomY, dimensions.rightX, dimensions.topY, glX, glY))
		{
			Runnable runnable = () -> {};
			onClick = new _MouseOver<>(null, new EventHandler(runnable, runnable, runnable));
		}
		// Draw the title.
		_drawLabel(dimensions.leftX, dimensions.topY - WINDOW_TITLE_HEIGHT, dimensions.topY, title.toUpperCase());
		
		// We want to draw these in a grid, in rows.  Leave space for the right margin since we count the left margin in the element sizing.
		float xSpace = dimensions.rightX - dimensions.leftX - dimensions.margin;
		float ySpace = dimensions.topY - dimensions.bottomY - dimensions.margin;
		// The size of each item is the margin before the element and the element itself.
		float spacePerElement = dimensions.elementSize + dimensions.margin;
		int itemsPerRow = (int) Math.round(Math.floor(xSpace / spacePerElement));
		int rowsPerPage = (int) Math.round(Math.floor(ySpace / spacePerElement));
		int itemsPerPage = itemsPerRow * rowsPerPage;
		int xElement = 0;
		int yElement = 0;
		
		float leftMargin = dimensions.leftX + dimensions.margin;
		// Leave space for top margin and title.
		float topMargin = dimensions.topY - WINDOW_TITLE_HEIGHT - dimensions.margin;
		int totalItems = values.size();
		int pageCount = ((totalItems - 1) / itemsPerPage) + 1;
		if (state.currentPage >= pageCount)
		{
			// Something changed underneath us so just reset.
			state.currentPage = 0;
		}
		int startingIndex = state.currentPage * itemsPerPage;
		int firstIndexBeyondPage = startingIndex + itemsPerPage;
		if (firstIndexBeyondPage > totalItems)
		{
			firstIndexBeyondPage = totalItems;
		}
		for (T value : values.subList(startingIndex, firstIndexBeyondPage))
		{
			// We want to render these left->right, top->bottom but GL is left->right, bottom->top so we increment X and Y in opposite ways.
			float left = leftMargin + (xElement * spacePerElement);
			float top = topMargin - (yElement * spacePerElement);
			float bottom = top - dimensions.elementSize;
			float right = left + dimensions.elementSize;
			boolean isMouseOver = _isMouseOver(left, bottom, right, top, glX, glY);
			EventHandler handler = renderer.render(left, bottom, dimensions.elementSize, isMouseOver, value);
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
		
		// Draw our pagination buttons if they make sense.
		if (pageCount > 1)
		{
			boolean canPageBack = (state.currentPage > 0);
			boolean canPageForward = (state.currentPage < (pageCount - 1));
			float textHeight = 0.05f;
			float buttonTop = dimensions.topY - 0.05f;
			float buttonBase = buttonTop - textHeight;
			boolean isMouseOver = _drawTextButton(dimensions.rightX - 0.25f, buttonBase, buttonTop, "<", glX, glY, canPageBack);
			if (isMouseOver)
			{
				Runnable runnable = () -> {
					state.currentPage -= 1;
				};
				onClick = new _MouseOver<>(null, new EventHandler(runnable, runnable, runnable));
			}
			String label = (state.currentPage + 1) + " / " + pageCount;
			_drawLabel(dimensions.rightX - 0.2f, buttonBase, buttonTop, label);
			isMouseOver = _drawTextButton(dimensions.rightX - 0.1f, buttonBase, buttonTop, ">", glX, glY, canPageForward);
			if (isMouseOver)
			{
				Runnable runnable = () -> {
					state.currentPage += 1;
				};
				onClick = new _MouseOver<>(null, new EventHandler(runnable, runnable, runnable));
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

	private void _drawTextOnBackground(float left, float top, String name)
	{
		TextManager.Element element = _textManager.lazilyLoadStringTexture(name.toUpperCase());
		float labelHeight = 0.1f;
		
		float bottom = top - labelHeight;
		float right = left + element.aspectRatio() * (top - bottom);
		_drawBackground(_backgroundFrameTexture_Common, left, bottom, right, top);
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
		
		float left = glX;
		float windowBottom = glY - labelHeight - itemSize - (2.0f * itemMargin);
		float textBottom = glY - labelHeight;
		float top = glY;
		float windowRight = left + Math.max(labelWidth, ingredientsWidth);
		float textRight = left + labelWidth;
		_drawBackground(_backgroundFrameTexture_Common, left, windowBottom, windowRight, top);
		_drawTextElement(left, textBottom, textRight, top, element.textureObject());
		
		float inputLeft = left + itemMargin;
		float inputTop = textBottom - itemMargin;
		float inputBottom = inputTop - itemSize;
		for (Items items : craft.input)
		{
			_drawItem(items.type(), items.count(), _backgroundFrameTexture_Common, inputLeft, inputBottom, itemSize, false, 0.0f);
			inputLeft += itemSize + itemMargin;
		}
	}

	private boolean _drawTextButton(float left, float bottom, float top, String label, float glX, float glY, boolean canHighlight)
	{
		TextManager.Element element = _textManager.lazilyLoadStringTexture(label);
		float textureAspect = element.aspectRatio();
		float right = left + textureAspect * (top - bottom);
		_drawBackground(_backgroundFrameTexture_Common, left, bottom, right, top);
		_drawTextElement(left, bottom, right, top, element.textureObject());
		
		// See if the mouse is in this rect.
		boolean shouldHighlight = canHighlight && _isMouseOver(left, bottom, right, top, glX, glY);
		if (shouldHighlight)
		{
			// If we want to highlight this, draw the highlight square over this.
			_gl.glActiveTexture(GL20.GL_TEXTURE0);
			_gl.glBindTexture(GL20.GL_TEXTURE_2D, _highlightTexture);
			_drawUnitRect(left, bottom, right, top);
		}
		return shouldHighlight;
	}

	private static boolean _isMouseOver(float left, float bottom, float right, float top, float glX, float glY)
	{
		return ((left <= glX) && (glX <= right) && (bottom <= glY) && (glY <= top));
	}

	private Inventory _getEntityInventory()
	{
		Inventory inventory = _entity.isCreativeMode()
				? CreativeInventory.fakeInventory()
				: _entity.inventory()
		;
		return inventory;
	}


	public static record EventHandler(Runnable click
			, Runnable rightClick
			, Runnable shiftClick
	) {}

	// selectedStation can be null if we have windows open but are looking at the floor.
	// Our layout currently just has the 3 fixed windows but each can have a state.
	private static record _WindowMode(AbsoluteLocation selectedStation
			, boolean inFuelInventory
			, _WindowState topLeft
			, _WindowState topRight
			, _WindowState bottom
	)
	{}

	private static record _MouseOver<T>(T context
			, EventHandler handler
	) {}

	private static record _WindowDimensions(float leftX
			, float bottomY
			, float rightX
			, float topY
			, float elementSize
			, float margin
	) {}

	/**
	 * Window state is meant to be mutable to track the interactive state of a specific window.
	 * Note that none of its state should be considered authoritative since the world will change underneath it so it
	 * must always be able to default back to something sensible.
	 * Additionally, these can be destroyed/replaced whenever the meaning of the window changes or is closed.
	 * Also note that the presence of such a state object does not imply that that window is actually defined.
	 */
	private static class _WindowState
	{
		// Page is 0-indexed (even though we display it as "X/Y" for 1-indexed values, in the UI).
		public int currentPage = 0;
	}
}
