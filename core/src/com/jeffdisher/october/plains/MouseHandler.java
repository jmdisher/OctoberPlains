package com.jeffdisher.october.plains;

import com.jeffdisher.october.types.AbsoluteLocation;
import com.jeffdisher.october.types.EntityLocation;


/**
 * Used to provide a high-level interface over mouse movements within the GL scene.
 */
public class MouseHandler
{
	private final int _tilesSquare;
	private EntityLocation _centre;

	public MouseHandler(int tilesSquare)
	{
		_tilesSquare = tilesSquare;
	}

	/**
	 * Called when the entity moves so we can account for the change in focus.
	 * 
	 * @param location The new centre entity location.
	 */
	public void setCentreLocation(EntityLocation location)
	{
		_centre = location;
	}

	/**
	 * Gets the (x,y,z) block location where the mouse currently is.
	 * 
	 * @param glX The x location, in GL coordinates ([-1.0 .. 1.0]).
	 * @param glY The y location, in GL coordinates ([-1.0 .. 1.0]).
	 * @param zOffset The z offset from the current entity location to use for the 3rd dimension.
	 * @return The location of the corresponding block.
	 */
	public AbsoluteLocation getXyzTile(float glX, float glY, int zOffset)
	{
		// The glX and glY are in the range of [-1.0 .. 1.0] so convert that into tile coordinates.
		// We also want to shift by half a tile so the mouse follows the centre, not the bottom-left.
		int xTile = Math.round((float)_tilesSquare * glX + _centre.x() - 0.5f);
		int yTile = Math.round((float)_tilesSquare * glY + _centre.y() - 0.5f);
		int zTile = (int)_centre.z() + zOffset;
		return new AbsoluteLocation(xTile, yTile, zTile);
	}
}
