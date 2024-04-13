package com.jeffdisher.october.plains;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Map;

import javax.imageio.ImageIO;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.GL20;
import com.jeffdisher.october.types.Item;
import com.jeffdisher.october.utils.Assert;


/**
 * Maintains the high-level representation of the texture atlases.
 * Note that there is a primary texture atlas (containing item textures, by number) and a secondary texture atlas
 * (contains misc textures).
 */
public class TextureAtlas
{
	/**
	 * The names of the special textures in the secondary atlas.
	 */
	public static enum Secondary
	{
		NONE,
		PLAYER,
		DEBRIS,
		BREAK_LIGHT,
		BREAK_MEDIUM,
		BREAK_HEAVY,
		ACTIVE_STATION,
	};

	public static TextureAtlas loadAtlas(GL20 gl
			, Item[] primaryItems
			, Map<Secondary, String> secondaryNameMap
	) throws IOException
	{
		// We will create 2 texture atlases:  Primary holds the item graphics and secondary holds modifiers to blend on top.
		// We will assume a fixed texture size of 32-square.
		int eachTextureEdge = 32;
		
		// Just grab the names of the items, assuming they are all PNGs:
		String[] primaryNames = Arrays.stream(primaryItems).map(
				(Item item) -> (item.id() + ".png")
		).toArray(
				(int size) -> new String[size]
		);
		int primaryTexturesPerRow = _texturesPerRow(primaryNames.length);
		int primaryTexture = _createTextureAtlas(gl, primaryNames, primaryTexturesPerRow, eachTextureEdge);
		
		String[] secondaryNames = new String[Secondary.values().length];
		for (Secondary secondary : Secondary.values())
		{
			String secondaryName = secondaryNameMap.get(secondary);
			// We don't allow missing textures - this would be a static error.
			Assert.assertTrue(null != secondaryName);
			secondaryNames[secondary.ordinal()] = secondaryName;
		}
		int secondaryTexturesPerRow = _texturesPerRow(secondaryNames.length);
		int secondaryTexture = _createTextureAtlas(gl, secondaryNames, secondaryTexturesPerRow, eachTextureEdge);
		
		return new TextureAtlas(primaryTexture, secondaryTexture, primaryTexturesPerRow, secondaryTexturesPerRow);
	}


	private static int _texturesPerRow(int textureCount)
	{
		// We essentially just want the base2 logarithm of the array length rounded to the nearest power of 2.
		// (a faster and generalizable algorithm using leading zeros could be used but is less obvious than this, for now)
		int texturesPerRow = 1;
		if (textureCount > 1)
		{
			texturesPerRow = 2;
			if (textureCount > 4)
			{
				texturesPerRow = 4;
				if (textureCount > 16)
				{
					texturesPerRow = 8;
					Assert.assertTrue(textureCount <= 64);
				}
			}
		}
		return texturesPerRow;
	}

	private static int _createTextureAtlas(GL20 gl, String[] imageNames, int texturesPerRow, int eachTextureEdge) throws IOException
	{
		int width = texturesPerRow * eachTextureEdge;
		int height = texturesPerRow * eachTextureEdge;
		
		// 4 bytes per pixel since we are storing pixels as RGBA.
		int bytesToAllocate = width * height * 4;
		ByteBuffer textureBufferData = ByteBuffer.allocateDirect(bytesToAllocate);
		textureBufferData.order(ByteOrder.nativeOrder());
		
		// Load all the images and walk across them to fill the buffer.
		BufferedImage loadedTextures[] = new BufferedImage[imageNames.length];
		for (int i = 0; i < imageNames.length; ++i)
		{
			String name = imageNames[i];
			
			// TODO:  Change this when we have more than one texture and need to actually build the atlas.
			FileHandle unknownTextureFile = Gdx.files.internal(name);
			BufferedImage loadedTexture = ImageIO.read(unknownTextureFile.read());
			// We require all textures to be of fixed square size.
			Assert.assertTrue(loadedTexture.getWidth() == eachTextureEdge);
			Assert.assertTrue(loadedTexture.getHeight() == eachTextureEdge);
			loadedTextures[i] = loadedTexture;
		}
		
		for (int y = 0; y < height; ++y)
		{
			for (int x = 0; x < width; ++x)
			{
				// Figure out which texture to pull from.
				int textureIndex = (y / eachTextureEdge * texturesPerRow) + (x / eachTextureEdge);
				if (textureIndex < loadedTextures.length)
				{
					BufferedImage loadedTexture = loadedTextures[textureIndex];
					int localX = x % eachTextureEdge;
					int localY = y % eachTextureEdge;
					int pixel = loadedTexture.getRGB(localX, localY);
					// This data is pulled out as ARGB but we need to upload it as RGBA.
					byte a = (byte)((0xFF000000 & pixel) >> 24);
					byte r = (byte)((0x00FF0000 & pixel) >> 16);
					byte g = (byte)((0x0000FF00 & pixel) >> 8);
					byte b = (byte) (0x000000FF & pixel);
					textureBufferData.put(new byte[] { r, g, b, a });
				}
				else
				{
					textureBufferData.put(new byte[4]);
				}
			}
		}
		((java.nio.Buffer) textureBufferData).flip();
		
		// Create the texture and upload.
		int texture = gl.glGenTexture();
		gl.glBindTexture(GL20.GL_TEXTURE_2D, texture);
		gl.glTexImage2D(GL20.GL_TEXTURE_2D, 0, GL20.GL_RGBA, width, height, 0, GL20.GL_RGBA, GL20.GL_UNSIGNED_BYTE, textureBufferData);
		gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_S, GL20.GL_CLAMP_TO_EDGE);
		gl.glTexParameteri(GL20.GL_TEXTURE_2D, GL20.GL_TEXTURE_WRAP_T, GL20.GL_CLAMP_TO_EDGE);
		gl.glGenerateMipmap(GL20.GL_TEXTURE_2D);
		return texture;
	}


	public final int primaryTexture;
	public final int secondaryTexture;
	public final float primaryCoordinateSize;
	public final float secondaryCoordinateSize;
	private final int _primaryTexturesPerRow;
	private final int _secondaryTexturesPerRow;

	private TextureAtlas(int primaryTexture, int secondaryTexture, int primaryTexturesPerRow, int secondaryTexturesPerRow)
	{
		this.primaryTexture = primaryTexture;
		this.secondaryTexture = secondaryTexture;
		this.primaryCoordinateSize = 1.0f / (float)primaryTexturesPerRow;
		this.secondaryCoordinateSize = 1.0f / (float)secondaryTexturesPerRow;
		_primaryTexturesPerRow = primaryTexturesPerRow;
		_secondaryTexturesPerRow = secondaryTexturesPerRow;
	}

	/**
	 * Returns the UV base coordinates of the texture with the given index.
	 * 
	 * @param item The item to draw.
	 * @return {u, v} of texture base coordinates.
	 */
	public float[] baseOfPrimaryTexture(Item item)
	{
		int index = item.number();
		int row = index / _primaryTexturesPerRow;
		int column = index % _primaryTexturesPerRow;
		float u = this.primaryCoordinateSize * (float)column;
		float v = this.primaryCoordinateSize * (float)row;
		return new float[] {u, v};
	}

	/**
	 * Returns the UV base coordinates of the secondary texture with the given index.
	 * 
	 * @param special The special texture to draw.
	 * @return {u, v} of texture base coordinates.
	 */
	public float[] baseOfSecondaryTexture(Secondary special)
	{
		int index = special.ordinal();
		int row = index / _secondaryTexturesPerRow;
		int column = index % _secondaryTexturesPerRow;
		float u = this.secondaryCoordinateSize * (float)column;
		float v = this.secondaryCoordinateSize * (float)row;
		return new float[] {u, v};
	}
}
