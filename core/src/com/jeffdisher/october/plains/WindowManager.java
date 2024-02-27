package com.jeffdisher.october.plains;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.aspects.InventoryAspect;
import com.jeffdisher.october.data.BlockProxy;
import com.jeffdisher.october.registries.Craft;
import com.jeffdisher.october.registries.ItemRegistry;
import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.CraftOperation;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.Inventory;
import com.jeffdisher.october.types.Item;
import com.jeffdisher.october.types.Items;
import com.jeffdisher.october.types.MutableInventory;


/**
 * This class manages the various overlay windows in the system.
 */
public class WindowManager
{
	public static final float BUTTON_HEIGHT = 0.1f;
	public static final float BUTTON_BACKGROUND_ASPECT_RATIO = 7.0f;
	public static final float NO_PROGRESS = 0.0f;

	private final GL20 _gl;
	private final TextureAtlas _atlas;
	private final Function<AbsoluteLocation, BlockProxy> _blockLoader;
	private final TextManager _textManager;

	private int _program;
	private int _uOffset;
	private int _uScale;
	private int _uTexture;
	private int _uTextureBase;

	// When rendering block information in the overlay, we have a vertex buffer for the tile and one for text.
	private int _tileVertexBuffer;
	private int _labelVertexBuffer;

	private int _backgroundVertexBuffer;
	private int _backgroundTexture;
	private int _backgroundHighlightTexture;
	private Entity _entity;

	private _WindowMode _mode;
	private AbsoluteLocation _openInventoryLocation;

	public WindowManager(GL20 gl, TextureAtlas atlas, Function<AbsoluteLocation, BlockProxy> blockLoader)
	{
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
						+ "	vec2 texCoord = vec2((uScale.x * vTexture.x) + uTextureBase.x, (uScale.y * vTexture.y) + uTextureBase.y);\n"
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
		_tileVertexBuffer = _defineTileVertexBuffer(_gl, _atlas.coordinateSize);
		_labelVertexBuffer = _defineTextVertexBuffer(_gl);
		
		// Create the background rectangle for labels, etc.
		_backgroundVertexBuffer = _defineCommonVertices(gl, BUTTON_BACKGROUND_ASPECT_RATIO, 1.0f);
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

	public Consumer<ClientLogic> drawWindowsWithButtonCapture(float glX, float glY)
	{
		// Enable our program
		_gl.glUseProgram(_program);
		
		// If there is an item selected, show it.
		Item selectedItem = (null != _entity) ? _entity.selectedItem() : null;
		if (null != selectedItem)
		{
			int count = _entity.inventory().items.get(selectedItem).count();
			_drawItem(selectedItem, count, -0.3f, -0.9f, 0.7f, false, NO_PROGRESS);
		}
		
		// Handle the case where we might need to close the inventory window if the block was destroyed or we are too far away.
		if (_WindowMode.CRAFTING_TABLE == _mode)
		{
			int absX = Math.abs(_openInventoryLocation.x() - Math.round(_entity.location().x()));
			int absY = Math.abs(_openInventoryLocation.y() - Math.round(_entity.location().y()));
			int absZ = Math.abs(_openInventoryLocation.z() - Math.round(_entity.location().z()));
			boolean isLocationClose = ((absX <= 2) && (absY <= 2) && (absZ <= 2));
			boolean isCraftingTable = (ItemRegistry.CRAFTING_TABLE == _blockLoader.apply(_openInventoryLocation).getItem());
			if (!isLocationClose || !isCraftingTable)
			{
				_mode = _WindowMode.NONE;
				_openInventoryLocation = null;
			}
		}
		
		// See if we should show the inventory window.
		// (note that the entity could only be null during start-up).
		Consumer<ClientLogic> button = null;
		if ((_WindowMode.NONE != _mode) && (null != _entity))
		{
			// Draw the entity inventory.
			float baseX = 0.0f;
			float baseY = 0.8f;
			Inventory entityInventory = _entity.inventory();
			Inventory blockInventory = (_mode == _WindowMode.FLOOR)
					? _currentBlockInventory()
					: _selectedBlockInventory()
			;
			for (Map.Entry<Item, Items> elt : entityInventory.items.entrySet())
			{
				Item item = elt.getKey();
				int count = elt.getValue().count();
				boolean shouldHighlight = _isOverButton(baseX, baseY, 0.7f, glX, glY);
				_drawItem(item, count, baseX, baseY, 0.7f, shouldHighlight, NO_PROGRESS);
				if (shouldHighlight)
				{
					button = (ClientLogic client) -> {
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
				
				// Now, draw the transfer buttons.
				float xferX = baseX + 0.5f;
				shouldHighlight = _isOverButton(xferX, baseY, 0.2f, glX, glY);
				_drawBackground(xferX, baseY, 0.2f, shouldHighlight);
				_drawLabel(xferX, baseY, "1");
				if (shouldHighlight)
				{
					button = (ClientLogic client) -> {
						AbsoluteLocation location = (null != _openInventoryLocation) ? _openInventoryLocation : GeometryHelpers.getCentreAtFeet(_entity);
						client.dropItemsInTile(location, item, 1);
					};
				}
				xferX += 0.3f;
				shouldHighlight = _isOverButton(xferX, baseY, 0.2f, glX, glY);
				_drawBackground(xferX, baseY, 0.2f, shouldHighlight);
				_drawLabel(xferX, baseY, "All");
				if (shouldHighlight)
				{
					button = (ClientLogic client) -> {
						// Find out how many can fit in the block.
						int inventoryCapacity = (_WindowMode.CRAFTING_TABLE == _mode) ? InventoryAspect.CAPACITY_CRAFTING_TABLE : InventoryAspect.CAPACITY_AIR;
						MutableInventory checker = new MutableInventory((null != blockInventory) ? blockInventory : Inventory.start(inventoryCapacity).finish());
						int max = checker.maxVacancyForItem(item);
						int toDrop = Math.min(count, max);
						if (toDrop > 0)
						{
							AbsoluteLocation location = (null != _openInventoryLocation) ? _openInventoryLocation : GeometryHelpers.getCentreAtFeet(_entity);
							client.dropItemsInTile(location, item, toDrop);
						}
					};
				}
				baseY -= 0.2f;
			}
			
			// Draw the inventory of the ground or selected block.
			if (null != blockInventory)
			{
				baseX = -1.0f;
				baseY = -0.2f;
				for (Map.Entry<Item, Items> elt : blockInventory.items.entrySet())
				{
					Item item = elt.getKey();
					int count = elt.getValue().count();
					// We never highlight the label, just the buttons.
					_drawItem(item, count, baseX, baseY, 0.7f, false, NO_PROGRESS);
					float xferX = baseX + 0.5f;
					boolean shouldHighlight = _isOverButton(xferX, baseY, 0.2f, glX, glY);
					_drawBackground(xferX, baseY, 0.2f, shouldHighlight);
					_drawLabel(xferX, baseY, "1");
					if (shouldHighlight)
					{
						button = (ClientLogic client) -> {
							AbsoluteLocation location = (null != _openInventoryLocation) ? _openInventoryLocation : GeometryHelpers.getCentreAtFeet(_entity);
							client.pickUpItemsFromTile(location, item, 1);
						};
					}
					xferX += 0.3f;
					shouldHighlight = _isOverButton(xferX, baseY, 0.2f, glX, glY);
					_drawBackground(xferX, baseY, 0.2f, shouldHighlight);
					_drawLabel(xferX, baseY, "All");
					if (shouldHighlight)
					{
						button = (ClientLogic client) -> {
							// Find out how many we can hold.
							MutableInventory checker = new MutableInventory(_entity.inventory());
							int max = checker.maxVacancyForItem(item);
							int toPickUp = Math.min(count, max);
							if (toPickUp > 0)
							{
								AbsoluteLocation location = (null != _openInventoryLocation) ? _openInventoryLocation : GeometryHelpers.getCentreAtFeet(_entity);
								client.pickUpItemsFromTile(location, item, toPickUp);
							}
						};
					}
					baseY -= 0.2f;
				}
			}
			
			// Draw the crafting panel.
			// Note that the crafting panel will act a bit differently whether it is the player's inventory or a crafting table.
			baseX = -1.0f;
			baseY = 0.8f;
			CraftOperation crafting;
			Inventory craftingInventory;
			if (_WindowMode.CRAFTING_TABLE == _mode)
			{
				// We are looking at the crafting table so grab its crafting aspect.
				crafting = _selectedBlockCrafting();
				craftingInventory = blockInventory;
			}
			else
			{
				// This is the player's inventory so use their operation.
				crafting = (null != _entity) ? _entity.localCraftOperation() : null;
				craftingInventory = entityInventory;
			}
			for (Craft craft : Craft.values())
			{
				// We will only check the highlight if this is something we even could craft.
				// Note that this needs to handle 
				boolean canCraft = (null != craftingInventory) ? craft.canApply(craftingInventory) : false;
				boolean shouldHighlight = canCraft && _isOverButton(baseX, baseY, 0.7f, glX, glY);
				// Check to see if this is something we are currently crafting.
				float progressBar = 0.0f;
				if ((null != crafting) && (crafting.selectedCraft() == craft))
				{
					progressBar = (float)crafting.completedMillis() / (float)craft.millisPerCraft;
				}
				_drawItem(craft.output.type(), craft.output.count(), baseX, baseY, 0.7f, shouldHighlight, progressBar);
				if (shouldHighlight)
				{
					if (_WindowMode.CRAFTING_TABLE == _mode)
					{
						// Craft in table.
						button = (ClientLogic client) -> {
							if (craft.canApply(craftingInventory))
							{
								client.beginCraftInBlock(_openInventoryLocation, craft);
							}
						};
					}
					else
					{
						// Craft in inventory.
						button = (ClientLogic client) -> {
							if (craft.canApply(craftingInventory))
							{
								client.beginCraft(craft);
							}
						};
					}
				}
				baseY -= 0.2f;
			}
		}
		return button;
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

	public boolean didOpenInventory(AbsoluteLocation block)
	{
		// See if there is an inventory we can open at the given block location.
		// NOTE:  We don't use this mechanism to talk about air blocks, only actual blocks.
		BlockProxy proxy = _blockLoader.apply(block);
		// Currently, this is only relevant for crafting table blocks.
		boolean didOpen = false;
		if (ItemRegistry.CRAFTING_TABLE == proxy.getItem())
		{
			// Enter crafting table mode at this block.
			_mode = _WindowMode.CRAFTING_TABLE;
			// We store the location symbolically, instead of directly storing the inventory, since it can change or be destroyed.
			_openInventoryLocation = block;
			didOpen = true;
		}
		return didOpen;
	}

	public AbsoluteLocation getOpenCraftingTable()
	{
		return _openInventoryLocation;
	}


	private static int _defineTileVertexBuffer(GL20 gl, float textureSize)
	{
		return _defineCommonVertices(gl, 1.0f, textureSize);
	}

	private static int _defineTextVertexBuffer(GL20 gl)
	{
		return _defineCommonVertices(gl, 4.0f, 1.0f);
	}

	private static int _defineCommonVertices(GL20 gl, float aspectRatio, float textureSize)
	{
		float height = BUTTON_HEIGHT;
		float width = aspectRatio * height;
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

	private void _drawItem(Item selectedItem, int count, float baseX, float baseY, float xScale, boolean shouldHighlight, float progressBar)
	{
		// We lazily create the label.
		short number = selectedItem.number();
		String name = selectedItem.name().toUpperCase();
		
		// Draw the background.
		if (progressBar > 0.0f)
		{
			// There is a progress bar, so draw that, instead.
			_drawBackground(baseX, baseY, xScale * progressBar, true);
		}
		else
		{
			// Just draw the normal background.
			_drawBackground(baseX, baseY, xScale, shouldHighlight);
		}
		
		// Draw the tile.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _atlas.texture);
		float[] uv = _atlas.baseOfTexture(number);
		float textureBaseU = uv[0];
		float textureBaseV = uv[1];
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, baseX, baseY);
		_gl.glUniform2f(_uScale, xScale, 1.0f);
		_gl.glUniform2f(_uTextureBase, textureBaseU, textureBaseV);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _tileVertexBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
		
		// Draw the number in the corner.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _textManager.lazilyLoadStringTexture(Integer.toString(count)));
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, baseX, baseY);
		_gl.glUniform2f(_uScale, 0.2f, 1.0f);
		_gl.glUniform2f(_uTextureBase, 0.0f, 0.0f);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _labelVertexBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
		
		// Draw the label.
		_drawLabel(baseX + 0.1f, baseY, name);
	}

	private static boolean _isOverButton(float baseX, float baseY, float xScale, float glX, float glY)
	{
		// The button background is 0.1 high and 0.7 wide.
		float edgeX = baseX + (xScale * BUTTON_BACKGROUND_ASPECT_RATIO * BUTTON_HEIGHT);
		float edgeY = baseY + BUTTON_HEIGHT;
		return ((glX >= baseX) && (glX <= edgeX) && (glY >= baseY) && (glY <= edgeY));
	}

	private Inventory _currentBlockInventory()
	{
		AbsoluteLocation block = GeometryHelpers.getCentreAtFeet(_entity);
		BlockProxy proxy = _blockLoader.apply(block);
		return proxy.getInventory();
	}

	private Inventory _selectedBlockInventory()
	{
		BlockProxy proxy = _blockLoader.apply(_openInventoryLocation);
		return proxy.getInventory();
	}

	private CraftOperation _selectedBlockCrafting()
	{
		BlockProxy proxy = _blockLoader.apply(_openInventoryLocation);
		return proxy.getCrafting();
	}

	private void _drawLabel(float baseX, float baseY, String label)
	{
		int labelTexture = _textManager.lazilyLoadStringTexture(label);
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, labelTexture);
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, baseX, baseY);
		_gl.glUniform2f(_uScale, 1.0f, 1.0f);
		_gl.glUniform2f(_uTextureBase, 0.0f, 0.0f);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _labelVertexBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
	}

	private void _drawBackground(float baseX, float baseY, float xScale, boolean shouldHighlight)
	{
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, shouldHighlight ? _backgroundHighlightTexture : _backgroundTexture);
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, baseX, baseY);
		_gl.glUniform2f(_uScale, xScale, 1.0f);
		_gl.glUniform2f(_uTextureBase, 0.0f, 0.0f);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _backgroundVertexBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
	}


	private static enum _WindowMode
	{
		// No windows visible.
		NONE,
		// We want to see our inventory and the floor.
		FLOOR,
		// We want to see our inventory and a crafting table.
		CRAFTING_TABLE,
	}
}
