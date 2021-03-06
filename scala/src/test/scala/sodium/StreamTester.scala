package sodium

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

import scala.collection.mutable.MutableList

class StreamTester {
  @After
  def tearDown(): Unit = {
    System.gc()
    Thread.sleep(100)
  }

  @Test
  def testSendStream(): Unit = {
    val e = new StreamSink[Int]()
    val out = new MutableList[Int]()
    val l = e.listen(out.+=(_))
    e.send(5)
    l.unlisten()
    assertEquals(List(5), out)
    e.send(6)
    assertEquals(List(5), out)
  }

  @Test
  def testMap(): Unit = {
    val e = new StreamSink[Int]()
    val m = e.map(x => x.toString)
    val out = new MutableList[String]()
    val l = m.listen(out.+=(_))
    e.send(5)
    l.unlisten()
    assertEquals(List("5"), out)
  }

  @Test
  def testMergeNonSimultaneous(): Unit = {
    val e1 = new StreamSink[Int]()
    val e2 = new StreamSink[Int]()
    val out = new MutableList[Int]()
    val l = e1.merge(e2).listen(out.+=(_))
    e1.send(7)
    e2.send(9)
    e1.send(8)
    l.unlisten()
    assertEquals(List(7, 9, 8), out)
  }

  @Test
  def testMergeSimultaneous(): Unit = {
    val e = new StreamSink[Int]()
    val out = new MutableList[Int]()
    val l = e.merge(e).listen(out.+=(_))
    e.send(7)
    e.send(9)
    l.unlisten()
    assertEquals(List(7, 7, 9, 9), out)
  }

  @Test
  def testMergeLeftBias(): Unit = {
    val e1 = new StreamSink[String]()
    val e2 = new StreamSink[String]()
    val out = new MutableList[String]()
    val l = e1.merge(e2).listen(out.+=(_))
    Transaction(_ => {
      e1.send("left1a")
      e1.send("left1b")
      e2.send("right1a")
      e2.send("right1b")
    })
    Transaction(_ => {
      e2.send("right2a")
      e2.send("right2b")
      e1.send("left2a")
      e1.send("left2b")
    })
    l.unlisten()
    assertEquals(List("left1a", "left1b", "right1a", "right1b", "left2a", "left2b", "right2a", "right2b"), out)
  }

  @Test
  def testCoalesce(): Unit = {
    val e1 = new StreamSink[Int]()
    val e2 = new StreamSink[Int]()
    val out = new MutableList[Int]()
    val l =
      e1.merge(e1.map(x => x * 100).merge(e2))
        .coalesce((a, b) => a + b)
        .listen(out.+=(_))
    e1.send(2)
    e1.send(8)
    e2.send(40)
    l.unlisten()
    assertEquals(List(202, 808, 40), out)
  }

  @Test
  def testFilter(): Unit = {
    val e = new StreamSink[Char]()
    val out = new MutableList[Char]()
    val l = e.filter(c => Character.isUpperCase(c)).listen(c => out.+=(c))
    List('H', 'o', 'I').foreach(e.send(_))
    l.unlisten()
    assertEquals(List('H', 'I'), out)
  }

  @Test
  def testFilterNotNull(): Unit = {
    val e = new StreamSink[String]()
    val out = new MutableList[String]()
    val l = e.filterNotNull().listen(s => out.+=(s))
    List("tomato", null, "peach").foreach(e.send(_))
    l.unlisten()
    assertEquals(List("tomato", "peach"), out)
  }

  @Test
  def testFilterOptional(): Unit = {
    val e = new StreamSink[Option[String]]()
    val out = new MutableList[String]()
    val l = Stream.filterOption(e).listen(s => out.+=(s))
    List(Some("tomato"), None, Some("peach")).foreach(e.send(_))
    l.unlisten()
    assertEquals(List("tomato", "peach"), out)
  }

  @Test
  def testLoopStream(): Unit = {
    val ea = new StreamSink[Int]()
    val ec = Transaction(_ => {
      val eb = new StreamLoop[Int]()
      val ec_ = ea.map(x => x % 10).merge(eb, (x, y) => x + y)
      val eb_out = ea.map(x => x / 10).filter(x => x != 0)
      eb.loop(eb_out)
      ec_
    })
    val out = new MutableList[Int]()
    val l = ec.listen(out.+=(_))
    List(2, 52).foreach(ea.send(_))
    l.unlisten()
    assertEquals(List(2, 7), out)
  }

  @Test
  def testGate(): Unit = {
    val ec = new StreamSink[Char]()
    val epred = new CellSink(true)
    val out = new MutableList[Char]()
    val l = ec.gate(epred).listen(out.+=(_))
    ec.send('H')
    epred.send(false)
    ec.send('O')
    epred.send(true)
    ec.send('I')
    l.unlisten()
    assertEquals(List('H', 'I'), out)
  }

  @Test
  def testCollect(): Unit = {
    val ea = new StreamSink[Int]()
    val out = new MutableList[Int]()
    val sum = ea.collect[Int, Int](100, (a, s) => (a + s, a + s))
    val l = sum.listen(out.+=(_))
    List(5, 7, 1, 2, 3).foreach(ea.send(_))
    l.unlisten()
    assertEquals(List(105, 112, 113, 115, 118), out)
  }

  @Test
  def testAccum(): Unit = {
    val ea = new StreamSink[Int]()
    val out = new MutableList[Int]()
    val sum = ea.accum[Int](100, (a, s) => a + s)
    val l = sum.updates().listen(out.+=(_))
    List(5, 7, 1, 2, 3).foreach(ea.send(_))
    l.unlisten()
    assertEquals(List(105, 112, 113, 115, 118), out)
  }

  @Test
  def testOnce(): Unit = {
    val e = new StreamSink[Char]()
    val out = new MutableList[Char]()
    val l = e.once().listen(out.+=(_))
    List('A', 'B', 'C').foreach(e.send(_))
    l.unlisten()
    assertEquals(List('A'), out)
  }

  @Test
  def testDefer(): Unit = {
    val e = new StreamSink[Char]()
    val b = e.hold(' ')
    val out = new MutableList[Char]()
    val l = e.defer().snapshot(b).listen(out.+=(_))
    List('C', 'B', 'A').foreach(e.send(_))
    l.unlisten()
    assertEquals(List('C', 'B', 'A'), out)
  }

  //Tests temporarily add to Scala version only
  @Test
  def testSplit(): Unit = {

    val ea = new StreamSink[Int]()
    val as = new StreamSink[List[Int]]()
    val out = new MutableList[Int]()
    val sum = ea.split(as).accum[Int](0, (a, b) => a + b)
    val l = sum.updates().listen(out.+=(_))
    as.send(List(100, 15, 60))
    as.send(List(1, 5))
    l.unlisten()
    assertEquals(List(100, 115, 175, 176, 181), out)
  }

}
