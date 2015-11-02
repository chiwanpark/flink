
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.ml.nn.util

import org.apache.flink.ml.math.{Breeze, Vector}
import Breeze._

import org.apache.flink.ml.metrics.distances.DistanceMetric

import scala.collection.mutable.ListBuffer
import scala.collection.mutable.PriorityQueue

/**
 * n-dimensional QuadTree data structure; partitions
 * spatial data for faster queries (e.g. KNN query)
 * The skeleton of the data structure was initially
 * based off of the 2D Quadtree found here:
 * http://www.cs.trinity.edu/~mlewis/CSCI1321-F11/Code/src/util/Quadtree.scala
 *
 * Many additional methods were added to the class both for
 * efficient KNN queries and generalizing to n-dim.
 *
 * @param minVec
 * @param maxVec
 */
class QuadTree(minVec:Vector, maxVec:Vector,distMetric:DistanceMetric){
  var maxPerBox = 20

  class Node(center:Vector,width:Vector, var children:Seq[Node]) {

    var objects = new ListBuffer[Vector]

    /** for testing purposes only; used in QuadTreeSuite.scala
      *
      * @return
      */
    def getCenterWidth(): (Vector, Vector) = {
      (center, width)
    }

    def contains(obj: Vector): Boolean = {
      overlap(obj, 0.0)
    }

    /** Tests if obj is within a radius of the node
      *
      * @param obj
      * @param radius
      * @return
      */
    def overlap(obj: Vector, radius: Double): Boolean = {
      var count = 0
      for (i <- 0 to obj.size - 1) {
        if (obj(i) - radius < center(i) + width(i) / 2 && obj(i) + radius > center(i) - width(i) / 2) {
          count += 1
        }
      }

      if (count == obj.size) {
        true
      } else {
        false
      }
    }

    /** Tests if obj is near a node:  minDist is defined so that every point in the box
      * has distance to obj greater than minDist
      * (minDist adopted from "Nearest Neighbors Queries" by N. Roussopoulos et al.)
      *
      * @param obj
      * @param radius
      * @return
      */
    def isNear(obj: Vector, radius: Double): Boolean = {
      if (minDist(obj) < radius) {
        true
      } else {
        false
      }
    }

    def minDist(obj: Vector): Double = {
      var minDist = 0.0
      for (i <- 0 to obj.size - 1) {
        if (obj(i) < center(i) - width(i) / 2) {
          minDist += math.pow(obj(i) - center(i) + width(i) / 2, 2)
        } else if (obj(i) > center(i) + width(i) / 2) {
          minDist += math.pow(obj(i) - center(i) - width(i) / 2, 2)
        }
      }
      minDist
    }

    def whichChild(obj: Vector): Int = {

      var count = 0
      for (i <- 0 to obj.size - 1) {
        if (obj(i) > center(i)) {
          count += Math.pow(2, obj.size -1 - i).toInt
          //count += Math.pow(2,i).toInt
        }
      }
      count
    }

    def makeChildren() {
      println("center before partitioning =  " + center)
      val centerClone = center.copy
      val cPart = partitionBox(center, width)
      println("cPart =  " + cPart)
      val mappedWidth = 0.5*width.asBreeze
      children = cPart.map(p => new Node(p, mappedWidth.fromBreeze, null))
      println("center after partitioning =  " + center)
      println("centerClone =  " + centerClone)

      center.map(i => centerClone)

      println("center after partitioning =  " + center)

    }

    /**
     * Recursive function that partitions a n-dim box by taking the (n-1) dimensional
     * plane through the center of the box keeping the n-th coordinate fixed,
     * then shifting it in the n-th direction up and down
     * and recursively applying partitionBox to the two shifted (n-1) dimensional planes.
     *
     * @return
     */
    def partitionBox(center: Vector, width: Vector): Seq[Vector] = {

      def partitionHelper(box: Seq[Vector], dim: Int): Seq[Vector] = {
        if (dim >= width.size) {
          box
        } else {
          val newBox = box.flatMap {
            vector =>
              val (up, down) = (vector.copy, vector)
              up.update(dim, up(dim) - width(dim) / 4)
              down.update(dim, down(dim) + width(dim) / 4)

              Seq(up,down)
          }
          partitionHelper(newBox, dim + 1)
        }
      }
      partitionHelper(Seq(center), 0)
    }
  }

  val root = new Node( ((minVec.asBreeze + maxVec.asBreeze)*0.5).fromBreeze,
    (maxVec.asBreeze - minVec.asBreeze).fromBreeze, null)

    /**
     *   simple printing of tree for testing/debugging
     */
  def printTree(){
    printTreeRecur(root)
  }

  def printTreeRecur(n:Node){
    if(n.children != null) {
      for (c <- n.children){
        printTreeRecur(c)
      }
    }else{
      println("printing tree: n.objects " + n.objects)
    }
  }

  /**
   * Recursively adds an object to the tree
   * @param obj
   */
  def insert(obj:Vector){
    insertRecur(obj,root)
  }

  private def insertRecur(obj:Vector,n:Node) {
    if(n.children==null) {
      if(n.objects.length < maxPerBox )
      {
        n.objects += obj
      }

      else{
        n.makeChildren()  ///make children nodes; place objects into them and clear node.objects
        for (o <- n.objects){
          insertRecur(o, n.children(n.whichChild(o)))
        }
        n.objects.clear()
        insertRecur(obj, n.children(n.whichChild(obj)))
      }
    } else{
      insertRecur(obj, n.children(n.whichChild(obj)))
    }
  }

  /** Following methods are used to zoom in on a region near a test point for a fast KNN query.
    *
    * This capability is used in the KNN query to find k "near" neighbors n_1,...,n_k, from
    * which one computes the max distance D_s to obj.  D_s is then used during the
    * kNN query to find all points within a radius D_s of obj using searchNeighbors.
    * To find the "near" neighbors, a min-heap is defined on the leaf nodes of the quadtree.
    * The priority of a leaf node is an appropriate notion of the distance between the test
    * point and the node, which is defined by minDist(obj),
   *
   */
  private def subOne(tuple: (Double,Node)) = tuple._1

  def searchNeighborsSiblingQueue(obj:Vector):ListBuffer[Vector] = {
    var ret = new ListBuffer[Vector]
    if (root.children == null) {   // edge case when the main box has not been partitioned at all
      root.objects
    } else {
      var NodeQueue = new PriorityQueue[(Double, Node)]()(Ordering.by(subOne))
      searchRecurSiblingQueue(obj, root, NodeQueue)

      var count = 0
      while (count < maxPerBox) {
        val dq = NodeQueue.dequeue()
        if (dq._2.objects.nonEmpty) {
          ret ++= dq._2.objects
          count += dq._2.objects.length
        }
      }
      ret
    }
}

  private def searchRecurSiblingQueue(obj:Vector,n:Node,
                                      NodeQueue:PriorityQueue[(Double, Node)]) {
    if(n.children != null) {
      for(child <- n.children; if child.contains(obj)) {
        if (child.children == null) {
          for (c <- n.children) {
            ////// Go down to minimal bounding box
            MinNodes(obj,c,NodeQueue)
          }
        }
        else {
            searchRecurSiblingQueue(obj, child, NodeQueue)
        }
      }
    }
  }

  private def MinNodes(obj:Vector,n:Node, NodeQueue:PriorityQueue[(Double, Node)]) {
    if (n.children == null){
      NodeQueue += ((-n.minDist(obj), n))
    } else{
      for (c <- n.children) {
          MinNodes(obj,c, NodeQueue)
        }
    }
  }

  /** Finds all objects within a neigiborhood of obj of a specified radius
    * scope is modified from original 2D version in:
    * http://www.cs.trinity.edu/~mlewis/CSCI1321-F11/Code/src/util/Quadtree.scala
    *
    * original version only looks in minimal box; for the KNN Query, we look at
    * all nearby boxes. The radius is determined from searchNeighborsSiblingQueue
    * by defining a min-heap on the leaf nodes
   *
   * @param obj
   * @param radius
   * @return
   */
  def searchNeighbors(obj:Vector,radius:Double):ListBuffer[Vector] = {
    var ret = new ListBuffer[Vector]
    searchRecur(obj,radius,root,ret)
    ret
  }

  private def searchRecur(obj:Vector,radius:Double,n:Node,ret:ListBuffer[Vector]) {
    if(n.children==null) {
      ret ++= n.objects
    } else {
      for(child <- n.children; if child.isNear(obj,radius)) {
        searchRecur(obj, radius, child, ret)
      }
    }
  }

   def distance(a:Vector,b:Vector):Double = {
     distMetric.distance(a,b)
  }
}