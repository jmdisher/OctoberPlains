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
import com.jeffdisher.october.types.EntityType;
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
	 * The names of the special textures in the auxiliary atlas.
	 */
	public static enum Auxiliary
	{
		NONE,
		DEBRIS,
		BREAK_LIGHT,
		BREAK_MEDIUM,
		BREAK_HEAVY,
		ACTIVE_STATION,
	};

	public static TextureAtlas loadAtlas(GL20 gl
			, Item[] tileItems
			, Map<EntityType, String> entityNameMap
			, Map<Auxiliary, String> auxiliaryNameMap
			, String missingTextureName
	) throws IOException
	{
		// We will create 2 texture atlases:  Primary holds the item graphics and secondary holds modifiers to blend on top.
		// We will assume a fixed texture size of 32-square.
		int eachTextureEdge = 32;
		
		// Just grab the names of the items, assuming they are all PNGs:
		String[] primaryNames = Arrays.stream(tileItems).map(
				(Item item) -> (item.id() + ".png")
		).toArray(
				(int size) -> new String[size]
		);
		int tileTexturesPerRow = _texturesPerRow(primaryNames.length);
		int tileTexture = _createTextureAtlas(gl, primaryNames, missingTextureName, tileTexturesPerRow, eachTextureEdge);
		
		String[] entityNames = new String[EntityType.values().length];
		for (EntityType type : EntityType.values())
		{
			String typeName = entityNameMap.get(type);
			// It is possible that we didn't find this if it is new or just an error value so let this fall through.
			entityNames[type.ordinal()] = typeName;
		}
		int entityTexturesPerRow = _texturesPerRow(entityNames.length);
		int entityTexture = _createTextureAtlas(gl, entityNames, missingTextureName, entityTexturesPerRow, eachTextureEdge);
		
		String[] auxNames = new String[Auxiliary.values().length];
		for (Auxiliary aux : Auxiliary.values())
		{
			String auxName = auxiliaryNameMap.get(aux);
			// We don't allow missing textures - this would be a static error.
			Assert.assertTrue(null != auxName);
			auxNames[aux.ordinal()] = auxName;
		}
		int auxTexturesPerRow = _texturesPerRow(auxNames.length);
		int auxTexture = _createTextureAtlas(gl, auxNames, missingTextureName, auxTexturesPerRow, eachTextureEdge);
		
		return new TextureAtlas(tileTexture, entityTexture, auxTexture, tileTexturesPerRow, entityTexturesPerRow, auxTexturesPerRow);
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
					if (textureCount > 64)
					{
						texturesPerRow = 16;
						Assert.assertTrue(textureCount <= 256);
					}
				}
			}
		}
		return texturesPerRow;
	}

	private static int _createTextureAtlas(GL20 gl, String[] imageNames, String missingTextureName, int texturesPerRow, int eachTextureEdge) throws IOException
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
			
			// If this is missing, load the missing texture, instead.
			FileHandle unknownTextureFile;
			if (null != name)
			{
				unknownTextureFile = Gdx.files.internal(name);
				// If this is missing, load the missing texture, instead.
				if (!unknownTextureFile.exists())
				{
					unknownTextureFile = Gdx.files.internal(missingTextureName);
				}
			}
			else
			{
				unknownTextureFile = Gdx.files.internal(missingTextureName);
			}
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


	public final int tileTextures;
	public final int entityTextures;
	public final int auxTextures;
	public final float tileCoordinateSize;
	public final float entityCoordinateSize;
	public final float auxCoordinateSize;
	private final int _tileTexturesPerRow;
	private final int _entityTexturesPerRow;
	private final int _auxTexturesPerRow;

	private TextureAtlas(int tileTextures, int entityTextures, int auxTextures, int tileTexturesPerRow, int entityTexturesPerRow, int auxTexturesPerRow)
	{
		this.tileTextures = tileTextures;
		this.entityTextures = entityTextures;
		this.auxTextures = auxTextures;
		this.tileCoordinateSize = 1.0f / (float)tileTexturesPerRow;
		this.entityCoordinateSize = 1.0f / (float)entityTexturesPerRow;
		this.auxCoordinateSize = 1.0f / (float)auxTexturesPerRow;
		_tileTexturesPerRow = tileTexturesPerRow;
		_entityTexturesPerRow = entityTexturesPerRow;
		_auxTexturesPerRow = auxTexturesPerRow;
	}

	/**
	 * Returns the UV base coordinates of the tile texture with the given index.
	 * 
	 * @param item The item to draw.
	 * @return {u, v} of texture base coordinates.
	 */
	public float[] baseOfTileTexture(Item item)
	{
		int index = item.number();
		int row = index / _tileTexturesPerRow;
		int column = index % _tileTexturesPerRow;
		float u = this.tileCoordinateSize * (float)column;
		float v = this.tileCoordinateSize * (float)row;
		return new float[] {u, v};
	}

	/**
	 * Returns the UV base coordinates of the entity texture with the given index.
	 * 
	 * @param entityType The entity texture to draw.
	 * @return {u, v} of texture base coordinates.
	 */
	public float[] baseOfEntityTexture(EntityType entityType)
	{
		// TODO:  Change this once we have the textures for entity types.
		int index = entityType.ordinal();
		int row = index / _entityTexturesPerRow;
		int column = index % _entityTexturesPerRow;
		float u = this.entityCoordinateSize * (float)column;
		float v = this.entityCoordinateSize * (float)row;
		return new float[] {u, v};
	}

	/**
	 * Returns the UV base coordinates of the auxilliary texture with the given index.
	 * 
	 * @param special The special texture to draw.
	 * @return {u, v} of texture base coordinates.
	 */
	public float[] baseOfAuxTexture(Auxiliary special)
	{
		int index = special.ordinal();
		int row = index / _auxTexturesPerRow;
		int column = index % _auxTexturesPerRow;
		float u = this.auxCoordinateSize * (float)column;
		float v = this.auxCoordinateSize * (float)row;
		return new float[] {u, v};
	}
}
