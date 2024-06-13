package com.jeffdisher.october.plains;

import java.util.HashMap;
import java.util.Map;

import com.jeffdisher.october.types.EntityLocation;
import com.jeffdisher.october.types.PartialEntity;
import com.jeffdisher.october.utils.Assert;


/**
 * Used to provide a high-level interface over mouse movements within the GL scene.
 */
public class MouseHandler
{
	private final int _tilesSquare;
	private final Map<Integer, PartialEntity> _otherEntitiesById;
	private EntityLocation _centre;

	public MouseHandler(int tilesSquare)
	{
		_tilesSquare = tilesSquare;
		_otherEntitiesById = new HashMap<>();
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
	 * Gets the (x,y,z) location where the mouse currently is, in tile coordinates.
	 * 
	 * @param glX The x location, in GL coordinates ([-1.0 .. 1.0]).
	 * @param glY The y location, in GL coordinates ([-1.0 .. 1.0]).
	 * @param zOffset The z offset from the current entity location to use for the 3rd dimension.
	 * @return The location of the mouse in tile coordinates.
	 */
	public EntityLocation getXyzTile(float glX, float glY, int zOffset)
	{
		// The glX and glY are in the range of [-1.0 .. 1.0] so convert that into tile coordinates.
		return new EntityLocation(_getLogicalX(glX), _getLogicalY(glY), _centre.z() + (float)zOffset);
	}

	/**
	 * Sets or updates an entity which is NOT the player's entity.
	 * 
	 * @param entity The new or updated entity.
	 */
	public void setOtherEntity(PartialEntity entity)
	{
		_otherEntitiesById.put(entity.id(), entity);
	}

	/**
	 * Removes an entity from the scene.
	 * 
	 * @param entityId The ID of the entity to remove.
	 */
	public void removeOtherEntity(int entityId)
	{
		PartialEntity old = _otherEntitiesById.remove(entityId);
		// This must have already been here.
		Assert.assertTrue(null != old);
	}

	/**
	 * Gets the other entity currently under the mouse.
	 * 
	 * @param glX The x location, in GL coordinates ([-1.0 .. 1.0]).
	 * @param glY The y location, in GL coordinates ([-1.0 .. 1.0]).
	 * @return The entity under the mouse, null if there isn't one.
	 */
	public PartialEntity entityUnderMouse(float glX, float glY)
	{
		float logicalX = _getLogicalX(glX);
		float logicalY = _getLogicalY(glY);
		PartialEntity selectedEntity = null;
		for (PartialEntity otherEntity : _otherEntitiesById.values())
		{
			EntityLocation location = otherEntity.location();
			float scale = otherEntity.volume().width();
			float lowX = location.x();
			float highX = lowX + scale;
			float lowY = location.y();
			float highY = lowY + scale;
			if ((logicalX >= lowX) && (logicalX <= highX) && (logicalY >= lowY) && (logicalY <= highY))
			{
				selectedEntity = otherEntity;
				break;
			}
		}
		return selectedEntity;
	}


	private float _getLogicalX(float glX)
	{
		return (float)_tilesSquare * glX + _centre.x();
	}

	private float _getLogicalY(float glY)
	{
		return (float)_tilesSquare * glY + _centre.y();
	}
}
