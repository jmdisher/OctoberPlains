package com.jeffdisher.october.plains;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.types.Entity;
import com.jeffdisher.october.types.Inventory;
import com.jeffdisher.october.types.Item;


/**
 * This class manages the various overlay windows in the system.
 */
public class WindowManager
{
	public static final int TEXT_TEXTURE_WIDTH_PIXELS = 256;
	public static final int TEXT_TEXTURE_HEIGHT_PIXELS = 64;
	public static final float BUTTON_HEIGHT = 0.1f;
	public static final float BUTTON_BACKGROUND_ASPECT_RATIO = 7.0f;

	private final GL20 _gl;
	private final TextureAtlas _atlas;

	private int _program;
	private int _uOffset;
	private int _uTexture;
	private int _uTextureBase;

	// When rendering block information in the overlay, we have a vertex buffer for the tile and one for text.
	private int _tileVertexBuffer;
	private int _labelVertexBuffer;

	private int _backgroundVertexBuffer;
	private int _backgroundTexture;
	private int _backgroundHighlightTexture;
	private final Map<String, Integer> _textTextures;
	private Entity _entity;

	private boolean _showInventory;

	public WindowManager(GL20 gl, TextureAtlas atlas)
	{
		_gl = gl;
		_atlas = atlas;
		
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
						+ "varying vec2 vTexture;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	vTexture = aTexture;\n"
						+ "	gl_Position = vec4(aPosition.x + uOffset.x, aPosition.y + uOffset.y, 0.0, 1.0);\n"
						+ "}\n"
				, "#version 100\n"
						+ "precision mediump float;\n"
						+ "uniform sampler2D uTexture;\n"
						+ "uniform vec2 uTextureBase;\n"
						+ "varying vec2 vTexture;\n"
						+ "void main()\n"
						+ "{\n"
						+ "	vec2 texCoord = vec2(vTexture.x + uTextureBase.x, vTexture.y + uTextureBase.y);\n"
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
		
		_textTextures = new HashMap<>();
	}

	public void drawWindows(float glX, float glY)
	{
		// Enable our program
		_gl.glUseProgram(_program);
		
		// If there is an item selected, show it.
		Item selectedItem = (null != _entity) ? _entity.selectedItem() : null;
		if (null != selectedItem)
		{
			_drawItem(selectedItem, -0.3f, -0.9f, false);
		}
		
		// See if we should show the inventory window.
		if (_showInventory && (null != _entity))
		{
			float baseX = 0.2f;
			float baseY = 0.8f;
			Inventory inv = _entity.inventory();
			for (Item item : inv.items.keySet())
			{
				boolean shouldHighlight = _isOverButton(baseX, baseY, glX, glY);
				_drawItem(item, baseX, baseY, shouldHighlight);
				baseY -= 0.2f;
			}
		}
	}

	public void setEntity(Entity entity)
	{
		_entity = entity;
	}

	public void toggleInventory()
	{
		_showInventory = !_showInventory;
	}

	public Consumer<ClientLogic> findButton(float glX, float glY)
	{
		Consumer<ClientLogic> button = null;
		// If we have the inventory open, see if this is a button location.
		if (_showInventory)
		{
			float baseX = 0.2f;
			float baseY = 0.8f;
			Inventory inv = _entity.inventory();
			for (Item item : inv.items.keySet())
			{
				if (_isOverButton(baseX, baseY, glX, glY))
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
					break;
				}
				baseY -= 0.2f;
			}
		}
		return button;
	}


	private static void _renderTextToImage(GL20 gl, int texture, String text)
	{
		// We just use "something" for the font and size, for now - this will be made into something more specific, later.
		Font font = new Font("Arial", Font.BOLD, 64);
		BufferedImage image = new BufferedImage(TEXT_TEXTURE_WIDTH_PIXELS, TEXT_TEXTURE_HEIGHT_PIXELS, BufferedImage.TYPE_INT_ARGB);
		Graphics graphics = image.getGraphics();
		graphics.setFont(font);
		graphics.setColor(Color.WHITE);
		graphics.drawString(text, 0, TEXT_TEXTURE_HEIGHT_PIXELS);
		
		int channelsPerPixel = 2;
		ByteBuffer textureBufferData = ByteBuffer.allocateDirect(TEXT_TEXTURE_WIDTH_PIXELS * TEXT_TEXTURE_HEIGHT_PIXELS * channelsPerPixel);
		textureBufferData.order(ByteOrder.nativeOrder());
		for (int y = 0; y < TEXT_TEXTURE_HEIGHT_PIXELS; ++y)
		{
			for (int x = 0; x < TEXT_TEXTURE_WIDTH_PIXELS; ++x)
			{
				int pixel = image.getRGB(x, y);
				// This data is pulled out as ARGB but we need to upload it as LA.
				// We draw white so just get any channel and the alpha.
				byte a = (byte)((0xFF000000 & pixel) >> 24);
				byte b = (byte) (0x000000FF & pixel);
				textureBufferData.put(new byte[] { b, a });
			}
		}
		((java.nio.Buffer) textureBufferData).flip();
		
		gl.glBindTexture(GL20.GL_TEXTURE_2D, texture);
		gl.glTexSubImage2D(GL20.GL_TEXTURE_2D, 0, 0, 0, TEXT_TEXTURE_WIDTH_PIXELS, TEXT_TEXTURE_HEIGHT_PIXELS, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
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

	private void _drawItem(Item selectedItem, float baseX, float baseY, boolean shouldHighlight)
	{
		// We lazily create the label.
		short number = selectedItem.number();
		String name = "Block " + number;
		if (!_textTextures.containsKey(name))
		{
			int labelTexture = _gl.glGenTexture();
			_gl.glBindTexture(GL20.GL_TEXTURE_2D, labelTexture);
			_gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_LUMINANCE_ALPHA, TEXT_TEXTURE_WIDTH_PIXELS, TEXT_TEXTURE_HEIGHT_PIXELS, 0, GL20.GL_LUMINANCE_ALPHA, GL20.GL_UNSIGNED_BYTE, null);
			_renderTextToImage(_gl, labelTexture, name);
			_textTextures.put(name, labelTexture);
		}
		
		// Draw the background.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, shouldHighlight ? _backgroundHighlightTexture : _backgroundTexture);
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, baseX, baseY);
		_gl.glUniform2f(_uTextureBase, 0.0f, 0.0f);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _backgroundVertexBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
		
		// Draw the tile.
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, _atlas.texture);
		float[] uv = _atlas.baseOfTexture(number);
		float textureBaseU = uv[0];
		float textureBaseV = uv[1];
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, baseX, baseY);
		_gl.glUniform2f(_uTextureBase, textureBaseU, textureBaseV);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _tileVertexBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
		
		// Draw the label.
		int labelTexture = _textTextures.get(name);
		_gl.glActiveTexture(GL20.GL_TEXTURE0);
		_gl.glBindTexture(GL20.GL_TEXTURE_2D, labelTexture);
		_gl.glUniform1i(_uTexture, 0);
		_gl.glUniform2f(_uOffset, baseX + 0.2f, baseY);
		_gl.glUniform2f(_uTextureBase, 0.0f, 0.0f);
		_gl.glBindBuffer(GL20.GL_ARRAY_BUFFER, _labelVertexBuffer);
		_gl.glEnableVertexAttribArray(0);
		_gl.glVertexAttribPointer(0, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 0);
		_gl.glEnableVertexAttribArray(1);
		_gl.glVertexAttribPointer(1, 2, GL20.GL_FLOAT, false, 4 * Float.BYTES, 2 * Float.BYTES);
		_gl.glDrawArrays(GL20.GL_TRIANGLES, 0, 6);
	}

	private static boolean _isOverButton(float baseX, float baseY, float glX, float glY)
	{
		// The button background is 0.1 high and 0.7 wide.
		float edgeX = baseX + (BUTTON_BACKGROUND_ASPECT_RATIO * BUTTON_HEIGHT);
		float edgeY = baseY + BUTTON_HEIGHT;
		return ((glX >= baseX) && (glX <= edgeX) && (glY >= baseY) && (glY <= edgeY));
	}
}
